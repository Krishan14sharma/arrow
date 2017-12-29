package io.arrow.ank

import arrow.HK
import arrow.core.FunctionK
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.data.ListKW
import arrow.data.Try
import arrow.data.ev
import arrow.data.k
import arrow.syntax.applicativeerror.catch
import arrow.typeclasses.MonadError
import arrow.typeclasses.monadError
import com.github.lalyos.jfiglet.FigletFont
import org.intellij.markdown.MarkdownElementTypes.CODE_FENCE
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.accept
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.ast.visitors.RecursiveVisitor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.jetbrains.kotlin.backend.common.descriptors.synthesizedName
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

val extensionMappings = mapOf(
        "java" to "java",
        "kotlin" to "kts"
)

@Suppress("UNCHECKED_CAST")
inline fun <reified F> ankMonadErrorInterpreter(ME: MonadError<F, Throwable> = monadError()): FunctionK<AnkOpsHK, F> =
        object : FunctionK<AnkOpsHK, F> {
            override fun <A> invoke(fa: HK<AnkOpsHK, A>): HK<F, A> {
                val op = fa.ev()
                return when (op) {
                    is AnkOps.CreateTarget -> ME.catch({ createTargetImpl(op.source, op.target) })
                    is AnkOps.GetFileCandidates -> ME.catch({ getFileCandidatesImpl(op.target) })
                    is AnkOps.ReadFile -> ME.catch({ readFileImpl(op.source) })
                    is AnkOps.ParseMarkdown -> ME.catch({ parseMarkDownImpl(op.markdown) })
                    is AnkOps.ExtractCode -> ME.catch({ extractCodeImpl(op.source, op.tree) })
                    is AnkOps.CompileCode -> ME.catch({ compileCodeImpl(op.snippets, op.compilerArgs) })
                    is AnkOps.ReplaceAnkToLang -> ME.catch({ replaceAnkToLangImpl(op.compilationResults) })
                    is AnkOps.GenerateFiles -> ME.catch({ generateFilesImpl(op.candidates, op.newContents) })
                } as HK<F, A>
            }
        }

val SupportedMarkdownExtensions: Set<String> = setOf(
        "markdown",
        "mdown",
        "mkdn",
        "md",
        "mkd",
        "mdwn",
        "mdtxt",
        "mdtext",
        "text",
        "Rmd"
)

fun createTargetImpl(source: File, target: File): File {
    source.copyRecursively(target, overwrite = true)
    return target
}

fun getFileCandidatesImpl(target: File): ListKW<File> =
        ListKW(target.walkTopDown().filter {
            SupportedMarkdownExtensions.contains(it.extension.toLowerCase())
        }.toList())

fun readFileImpl(source: File): String =
        source.readText()

fun parseMarkDownImpl(markdown: String): ASTNode =
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)

abstract class NoStackTrace(msg: String) : Throwable(msg, null, false, false)

val ankErrorHeader = FigletFont.convertOneLine("classpath:/standard.flf", "ANK, Fix your docs!")

data class CompilationException(
        val file: File,
        val snippet: Snippet,
        val underlying: Throwable,
        val msg: String = """
            |
            |$ankErrorHeader
            |
            |```
            |${snippet.code}
            |```
            |
            |${underlying.message}
            |
            |file: $file
            |lang: ${snippet.lang}
            |
        """.trimMargin()) : NoStackTrace(msg) {
    override fun toString(): String = msg
}

data class CompiledMarkdown(val origin: File, val snippets: ListKW<Snippet>)

data class Snippet(
        val fence: String,
        val lang: String,
        val silent: Boolean,
        val startOffset: Int,
        val endOffset: Int,
        val code: String,
        val result: Option<String> = None)

fun extractCodeImpl(source: String, tree: ASTNode): ListKW<Snippet> {
    val sb = mutableListOf<Snippet>()
    tree.accept(object : RecursiveVisitor() {
        override fun visitNode(node: ASTNode) {
            if (node.type == CODE_FENCE) {
                val fence = node.getTextInNode(source)
                val lang = fence.takeWhile { it != ':' }.toString().replace("```", "")
                if (fence.startsWith("```$lang$AnkBlock")) {
                    val code = fence.split("\n").drop(1).dropLast(1).joinToString("\n")
                    sb.add(Snippet(fence.toString(), lang, fence.startsWith("```$AnkSilentBlock"), node.startOffset, node.endOffset, code))
                }
            }
            super.visitNode(node)
        }
    })
    return sb.k()
}

fun compileCodeImpl(snippets: Map<File, ListKW<Snippet>>, classpath: ListKW<String>): ListKW<CompiledMarkdown> {
    println(":runAnk -> started compilation")
    return snippets.map { (file, codeBlocks) ->
        println(":runAnk -> compiling: ${file.parentFile.name}/${file.name}")
        val classLoader = URLClassLoader(classpath.map { URL(it) }.ev().list.toTypedArray())
        val seManager = ScriptEngineManager(classLoader)
        val engineCache: Map<String, ScriptEngine> =
                codeBlocks.list
                        .distinctBy { it.lang }
                        .map {
                            it.lang to seManager.getEngineByExtension(extensionMappings.getOrDefault(it.lang, "kts"))
                        }
                        .toMap()
        val evaledSnippets: ListKW<Snippet> = codeBlocks.map { snippet ->
            val result: Any? = Try {
                val engine: ScriptEngine = engineCache.k().getOrElse(
                        snippet.lang,
                        { throw CompilationException(file, snippet, IllegalStateException("No engine configured for `${snippet.lang}`")) })
                engine.eval(snippet.code)

            }.fold({
                throw CompilationException(file, snippet, it)
            }, { it })
            if (snippet.silent) {
                snippet
            } else {
                val resultString: Option<String> = Option.fromNullable(result).fold({ None }, { Some("//$it") })
                snippet.copy(result = resultString)
            }
        }
        CompiledMarkdown(file, evaledSnippets)
    }.k()
}

fun replaceAnkToLangImpl(compiledMarkdown: CompiledMarkdown): String =
        compiledMarkdown.snippets.fold(compiledMarkdown.origin.readText(), { content, snippet ->
            snippet.result.fold(
                    { content },
                    { content.replace(snippet.fence, "```${snippet.lang}\n" + snippet.code + "\n" + it + "\n```") }
            )
        })

fun generateFilesImpl(candidates: ListKW<File>, newContents: ListKW<String>): ListKW<File> =
        ListKW(candidates.mapIndexed { n, file ->
            file.printWriter().use {
                it.print(newContents.list[n])
            }
            file
        })
