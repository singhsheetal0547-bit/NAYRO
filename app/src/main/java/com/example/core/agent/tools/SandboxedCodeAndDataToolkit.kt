package com.example.core.agent.tools

import com.example.core.workspace.WorkspaceManager
import com.example.core.workspace.WorkspaceResult
import kotlin.math.abs
import kotlin.math.pow

data class SandboxExecutionResult(
    val isSuccess: Boolean,
    val stdout: String,
    val variables: Map<String, String>,
    val stepsExecuted: Int,
    val errorMessage: String? = null
)

data class ColumnStats(
    val columnName: String,
    val totalRows: Int,
    val nonEmptyCount: Int,
    val numericCount: Int,
    val sum: Double?,
    val avg: Double?,
    val min: Double?,
    val max: Double?
)

data class DataAnalysisResult(
    val isSuccess: Boolean,
    val format: String,
    val rowCount: Int,
    val columns: List<String>,
    val stats: List<ColumnStats>,
    val reportMarkdown: String,
    val errorMessage: String? = null
)

/**
 * Section 7 — Play-Policy Compliant Pure-Kotlin Sandboxed Code Execution,
 * Development Scaffolding, and Structured Data/Spreadsheet Analysis Toolkit.
 *
 * Security & Policy Guarantees:
 * - ZERO Dynamic Code Loading (no .dex/.jar/.so loading).
 * - ZERO OS process/shell invocation (no Runtime.exec / ProcessBuilder).
 * - ZERO arbitrary filesystem or network access; all file I/O is strictly confined to [WorkspaceManager].
 * - Deterministic execution step cap, output size cap, and wall-clock timeout cap prevent infinite loops & DoS.
 */
object SandboxedScriptRunner {
    const val MAX_STEPS = 5_000
    const val MAX_OUTPUT_CHARS = 8_192
    const val MAX_TIMEOUT_MS = 1_500L

    private val FORBIDDEN_PATTERNS = listOf(
        Regex("(?i)\\bimport\\s+(os|sys|subprocess|socket|shutil|urllib|requests|http|pathlib)\\b"),
        Regex("(?i)\\b__import__\\s*\\("),
        Regex("(?i)\\b(exec|eval|open|compile)\\s*\\("),
        Regex("(?i)\\b(Runtime\\.getRuntime|ProcessBuilder|java\\.io\\.File|java\\.net\\.)"),
        Regex("(?i)\\brequire\\s*\\(\\s*['\"](fs|child_process|net|http|https|os)['\"]\\s*\\)"),
        Regex("(?i)\\b(fetch|XMLHttpRequest|child_process)\\b")
    )

    fun execute(
        code: String,
        language: String = "python",
        maxSteps: Int = MAX_STEPS,
        timeoutMs: Long = MAX_TIMEOUT_MS
    ): SandboxExecutionResult {
        val raw = code.trim()
        if (raw.isEmpty()) {
            return SandboxExecutionResult(
                isSuccess = false,
                stdout = "",
                variables = emptyMap(),
                stepsExecuted = 0,
                errorMessage = "Script code cannot be empty."
            )
        }

        // Security check: reject forbidden OS/network/shell/file operations
        for (pattern in FORBIDDEN_PATTERNS) {
            val match = pattern.find(raw)
            if (match != null) {
                return SandboxExecutionResult(
                    isSuccess = false,
                    stdout = "",
                    variables = emptyMap(),
                    stepsExecuted = 0,
                    errorMessage = "Sandbox security blocked forbidden operation: '${match.value}'. Only pure computation & print output are allowed in the on-device sandbox."
                )
            }
        }

        val normalizedLines = normalizeScriptLines(raw, language)
        val env = linkedMapOf<String, Any?>()
        val output = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(100L, 5_000L)
        var steps = 0

        fun checkLimits() {
            steps++
            if (steps > maxSteps) {
                throw SandboxLimitException("Execution terminated: exceeded sandbox step limit ($maxSteps steps). Check for infinite loops.")
            }
            if (System.currentTimeMillis() > deadline) {
                throw SandboxLimitException("Execution terminated: exceeded sandbox timeout (${timeoutMs}ms).")
            }
            if (output.length > MAX_OUTPUT_CHARS) {
                throw SandboxLimitException("Execution terminated: stdout exceeded $MAX_OUTPUT_CHARS characters.")
            }
        }

        fun executeBlock(lines: List<IndexedLine>, startIdx: Int, endIdx: Int, expectedIndent: Int) {
            var i = startIdx
            while (i < endIdx) {
                checkLimits()
                val line = lines[i]
                val stmt = line.text.trim()
                if (stmt.isEmpty() || stmt.startsWith("#") || stmt.startsWith("//")) {
                    i++
                    continue
                }

                // for <var> in range(...) or for <var> in <listExpr>:
                val forRangeMatch = Regex("^for\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s+in\\s+range\\((.+)\\)\\s*:?$").find(stmt)
                val forListMatch = Regex("^for\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s+in\\s+(.+?)\\s*:?$").find(stmt)
                if (forRangeMatch != null) {
                    val varName = forRangeMatch.groupValues[1]
                    val args = splitTopLevelArgs(forRangeMatch.groupValues[2]).map { evalNumber(it, env).toInt() }
                    val rangeValues = when (args.size) {
                        1 -> (0 until args[0]).toList()
                        2 -> (args[0] until args[1]).toList()
                        3 -> {
                            val step = args[2]
                            if (step == 0) throw IllegalArgumentException("range() step cannot be 0")
                            val list = mutableListOf<Int>()
                            var cur = args[0]
                            while ((step > 0 && cur < args[1]) || (step < 0 && cur > args[1])) {
                                checkLimits()
                                list.add(cur)
                                cur += step
                            }
                            list
                        }
                        else -> throw IllegalArgumentException("Invalid range() arguments.")
                    }
                    val blockEnd = findBlockEnd(lines, i + 1, endIdx, line.indent)
                    for (v in rangeValues) {
                        checkLimits()
                        env[varName] = v.toDouble()
                        executeBlock(lines, i + 1, blockEnd, line.indent + 1)
                    }
                    i = blockEnd
                    continue
                } else if (forListMatch != null) {
                    val varName = forListMatch.groupValues[1]
                    val iterable = evalExpression(forListMatch.groupValues[2], env)
                    val items = when (iterable) {
                        is List<*> -> iterable
                        is String -> iterable.map { it.toString() }
                        else -> throw IllegalArgumentException("Cannot iterate over '$iterable'")
                    }
                    val blockEnd = findBlockEnd(lines, i + 1, endIdx, line.indent)
                    for (item in items) {
                        checkLimits()
                        env[varName] = item
                        executeBlock(lines, i + 1, blockEnd, line.indent + 1)
                    }
                    i = blockEnd
                    continue
                }

                // while <cond>:
                val whileMatch = Regex("^while\\s+(.+?)\\s*:?$").find(stmt)
                if (whileMatch != null) {
                    val condExpr = whileMatch.groupValues[1]
                    val blockEnd = findBlockEnd(lines, i + 1, endIdx, line.indent)
                    while (isTruthy(evalExpression(condExpr, env))) {
                        checkLimits()
                        executeBlock(lines, i + 1, blockEnd, line.indent + 1)
                    }
                    i = blockEnd
                    continue
                }

                // if <cond>: ... else: ...
                val ifMatch = Regex("^if\\s+(.+?)\\s*:?$").find(stmt)
                if (ifMatch != null) {
                    val condExpr = ifMatch.groupValues[1]
                    val ifBlockEnd = findBlockEnd(lines, i + 1, endIdx, line.indent)
                    val hasElse = ifBlockEnd < endIdx &&
                        lines[ifBlockEnd].indent == line.indent &&
                        lines[ifBlockEnd].text.trim().matches(Regex("^else\\s*:?$"))
                    val elseBlockEnd = if (hasElse) findBlockEnd(lines, ifBlockEnd + 1, endIdx, line.indent) else ifBlockEnd

                    if (isTruthy(evalExpression(condExpr, env))) {
                        executeBlock(lines, i + 1, ifBlockEnd, line.indent + 1)
                    } else if (hasElse) {
                        executeBlock(lines, ifBlockEnd + 1, elseBlockEnd, line.indent + 1)
                    }
                    i = elseBlockEnd
                    continue
                }

                // print(...) / console.log(...)
                val printMatch = Regex("^(?:print|console\\.log)\\((.*)\\)$").find(stmt)
                if (printMatch != null) {
                    val args = splitTopLevelArgs(printMatch.groupValues[1])
                    val rendered = args.joinToString(" ") { formatValue(evalExpression(it, env)) }
                    if (output.isNotEmpty()) output.append("\n")
                    output.append(rendered)
                    i++
                    continue
                }

                // Augmented assignment: x += expr, x -= expr, x *= expr, x /= expr
                val augMatch = Regex("^(?:(?:let|const|var)\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*(\\+=|-=|\\*=|/=)\\s*(.+)$").find(stmt)
                if (augMatch != null) {
                    val name = augMatch.groupValues[1]
                    val op = augMatch.groupValues[2]
                    val rhs = evalExpression(augMatch.groupValues[3], env)
                    val cur = env[name] ?: 0.0
                    env[name] = when (op) {
                        "+=" -> if (cur is String || rhs is String) "${formatValue(cur)}${formatValue(rhs)}" else toDouble(cur) + toDouble(rhs)
                        "-=" -> toDouble(cur) - toDouble(rhs)
                        "*=" -> toDouble(cur) * toDouble(rhs)
                        "/=" -> {
                            val d = toDouble(rhs)
                            if (d == 0.0) throw ArithmeticException("Division by zero")
                            toDouble(cur) / d
                        }
                        else -> rhs
                    }
                    i++
                    continue
                }

                // Standard assignment: [let|const|var] name = expr
                val assignMatch = Regex("^(?:(?:let|const|var)\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(.+)$").find(stmt)
                if (assignMatch != null && !stmt.contains("==")) {
                    val name = assignMatch.groupValues[1]
                    val value = evalExpression(assignMatch.groupValues[2], env)
                    env[name] = value
                    i++
                    continue
                }

                // Bare expression evaluation (useful for single-line expressions)
                val evaluated = evalExpression(stmt, env)
                env["_last"] = evaluated
                i++
            }
        }

        return try {
            executeBlock(normalizedLines, 0, normalizedLines.size, 0)
            val finalStdout = output.toString().ifBlank {
                env["_last"]?.let { formatValue(it) }.orEmpty()
            }
            SandboxExecutionResult(
                isSuccess = true,
                stdout = finalStdout,
                variables = env.filterKeys { it != "_last" }.mapValues { formatValue(it.value) },
                stepsExecuted = steps
            )
        } catch (e: SandboxLimitException) {
            SandboxExecutionResult(
                isSuccess = false,
                stdout = output.toString(),
                variables = env.filterKeys { it != "_last" }.mapValues { formatValue(it.value) },
                stepsExecuted = steps,
                errorMessage = e.message
            )
        } catch (e: Exception) {
            SandboxExecutionResult(
                isSuccess = false,
                stdout = output.toString(),
                variables = env.filterKeys { it != "_last" }.mapValues { formatValue(it.value) },
                stepsExecuted = steps,
                errorMessage = "Sandbox runtime error: ${e.message ?: e::class.simpleName}"
            )
        }
    }

    private class SandboxLimitException(message: String) : RuntimeException(message)

    private data class IndexedLine(val indent: Int, val text: String)

    private fun normalizeScriptLines(raw: String, language: String): List<IndexedLine> {
        val isJsLike = language.lowercase() in setOf("js", "javascript", "ts", "typescript")
        if (!isJsLike) {
            return raw.lines().mapNotNull { line ->
                val trimmedEnd = line.trimEnd()
                if (trimmedEnd.isBlank()) return@mapNotNull null
                val indent = trimmedEnd.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4 else 1 }
                IndexedLine(indent, trimmedEnd.trim().removeSuffix(";"))
            }
        }
        // Convert JS brace blocks into clean indentation levels so the unified interpreter runs both Python & JS
        val result = mutableListOf<IndexedLine>()
        var indentLevel = 0
        for (rawLine in raw.lines()) {
            var line = rawLine.trim().removeSuffix(";")
            if (line.isEmpty() || line.startsWith("//")) continue
            if (line.startsWith("}")) {
                indentLevel = (indentLevel - 1).coerceAtLeast(0)
                line = line.removePrefix("}").trim()
                if (line.isEmpty()) continue
            }
            val opensBlock = line.endsWith("{")
            if (opensBlock) {
                line = line.removeSuffix("{").trim()
                // Normalize JS `if (cond)` / `while (cond)` -> `if cond:` / `while cond:`
                line = line
                    .replaceFirst(Regex("^if\\s*\\((.+)\\)$"), "if $1:")
                    .replaceFirst(Regex("^while\\s*\\((.+)\\)$"), "while $1:")
                    .replaceFirst(Regex("^else$"), "else:")
            }
            result.add(IndexedLine(indentLevel * 4, line))
            if (opensBlock) indentLevel++
        }
        return result
    }

    private fun findBlockEnd(lines: List<IndexedLine>, startIdx: Int, limitIdx: Int, parentIndent: Int): Int {
        var idx = startIdx
        while (idx < limitIdx && lines[idx].indent > parentIndent) {
            idx++
        }
        return idx
    }

    private fun evalNumber(expr: String, env: Map<String, Any?>): Double = toDouble(evalExpression(expr, env))

    private fun evalExpression(rawExpr: String, env: Map<String, Any?>): Any? {
        val expr = rawExpr.trim()
        if (expr.isEmpty()) return ""

        // Strip outer matching parens
        if (expr.startsWith("(") && expr.endsWith(")") && isWrappedByMatchingDelimiters(expr, '(', ')')) {
            return evalExpression(expr.substring(1, expr.length - 1), env)
        }

        // String literal
        if ((expr.startsWith("\"") && expr.endsWith("\"") && expr.length >= 2 && expr.indexOf('"', 1) == expr.length - 1) ||
            (expr.startsWith("'") && expr.endsWith("'") && expr.length >= 2 && expr.indexOf('\'', 1) == expr.length - 1)
        ) {
            return expr.substring(1, expr.length - 1)
        }

        // List literal
        if (expr.startsWith("[") && expr.endsWith("]") && isWrappedByMatchingDelimiters(expr, '[', ']')) {
            val inner = expr.substring(1, expr.length - 1).trim()
            if (inner.isEmpty()) return emptyList<Any?>()
            return splitTopLevelArgs(inner).map { evalExpression(it, env) }
        }

        // Boolean / null literals
        when (expr.lowercase()) {
            "true" -> return true
            "false" -> return false
            "none", "null" -> return null
        }

        // Built-in pure functions: len, sum, min, max, abs, round
        val fnMatch = Regex("^(len|sum|min|max|abs|round|int|float|str)\\((.*)\\)$").find(expr)
        if (fnMatch != null && isWrappedByMatchingDelimiters(expr.substring(fnMatch.groupValues[1].length), '(', ')')) {
            val fn = fnMatch.groupValues[1]
            val args = splitTopLevelArgs(fnMatch.groupValues[2]).map { evalExpression(it, env) }
            return when (fn) {
                "len" -> when (val a = args.firstOrNull()) {
                    is List<*> -> a.size.toDouble()
                    is String -> a.length.toDouble()
                    else -> 0.0
                }
                "sum" -> {
                    val items = (args.firstOrNull() as? List<*>) ?: args
                    items.sumOf { toDouble(it) }
                }
                "min" -> {
                    val items = (args.firstOrNull() as? List<*>) ?: args
                    items.minOfOrNull { toDouble(it) } ?: 0.0
                }
                "max" -> {
                    val items = (args.firstOrNull() as? List<*>) ?: args
                    items.maxOfOrNull { toDouble(it) } ?: 0.0
                }
                "abs" -> abs(toDouble(args.firstOrNull()))
                "round" -> kotlin.math.round(toDouble(args.firstOrNull()))
                "int" -> toDouble(args.firstOrNull()).toLong().toDouble()
                "float" -> toDouble(args.firstOrNull())
                "str" -> formatValue(args.firstOrNull())
                else -> 0.0
            }
        }

        // Logical OR
        splitBinaryOp(expr, listOf(" or ", " || "))?.let { (l, _, r) ->
            return isTruthy(evalExpression(l, env)) || isTruthy(evalExpression(r, env))
        }
        // Logical AND
        splitBinaryOp(expr, listOf(" and ", " && "))?.let { (l, _, r) ->
            return isTruthy(evalExpression(l, env)) && isTruthy(evalExpression(r, env))
        }
        // Comparisons
        splitBinaryOp(expr, listOf("==", "!=", "<=", ">=", "<", ">"))?.let { (l, op, r) ->
            val lv = evalExpression(l, env)
            val rv = evalExpression(r, env)
            return when (op) {
                "==" -> formatValue(lv) == formatValue(rv)
                "!=" -> formatValue(lv) != formatValue(rv)
                "<=" -> toDouble(lv) <= toDouble(rv)
                ">=" -> toDouble(lv) >= toDouble(rv)
                "<" -> toDouble(lv) < toDouble(rv)
                ">" -> toDouble(lv) > toDouble(rv)
                else -> false
            }
        }
        // Addition & Subtraction
        splitBinaryOpRightToLeft(expr, listOf("+", "-"))?.let { (l, op, r) ->
            val lv = evalExpression(l, env)
            val rv = evalExpression(r, env)
            return if (op == "+") {
                if (lv is String || rv is String) "${formatValue(lv)}${formatValue(rv)}" else toDouble(lv) + toDouble(rv)
            } else {
                toDouble(lv) - toDouble(rv)
            }
        }
        // Multiplication, Division, Modulo
        splitBinaryOpRightToLeft(expr, listOf("*", "/", "%"))?.let { (l, op, r) ->
            val ld = toDouble(evalExpression(l, env))
            val rd = toDouble(evalExpression(r, env))
            return when (op) {
                "*" -> ld * rd
                "/" -> {
                    if (rd == 0.0) throw ArithmeticException("Division by zero")
                    ld / rd
                }
                "%" -> ld % rd
                else -> 0.0
            }
        }
        // Power
        splitBinaryOp(expr, listOf("**"))?.let { (l, _, r) ->
            return toDouble(evalExpression(l, env)).pow(toDouble(evalExpression(r, env)))
        }

        // Numeric literal
        expr.toDoubleOrNull()?.let { return it }

        // Variable lookup
        if (env.containsKey(expr)) return env[expr]

        throw IllegalArgumentException("Undefined variable or unsupported expression: '$expr'")
    }

    private fun splitTopLevelArgs(argStr: String): List<String> {
        val result = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (ch in argStr) {
            if (quote != null) {
                cur.append(ch)
                if (ch == quote) quote = null
                continue
            }
            when (ch) {
                '\'', '"' -> {
                    quote = ch
                    cur.append(ch)
                }
                '(', '[', '{' -> {
                    depth++
                    cur.append(ch)
                }
                ')', ']', '}' -> {
                    depth--
                    cur.append(ch)
                }
                ',' -> {
                    if (depth == 0) {
                        result.add(cur.toString().trim())
                        cur.clear()
                    } else {
                        cur.append(ch)
                    }
                }
                else -> cur.append(ch)
            }
        }
        val tail = cur.toString().trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result
    }

    private fun isWrappedByMatchingDelimiters(expr: String, open: Char, close: Char): Boolean {
        if (expr.length < 2 || expr.first() != open || expr.last() != close) return false
        var depth = 0
        var quote: Char? = null
        for (i in expr.indices) {
            val ch = expr[i]
            if (quote != null) {
                if (ch == quote) quote = null
                continue
            }
            if (ch == '"' || ch == '\'') {
                quote = ch
                continue
            }
            if (ch == open) depth++
            if (ch == close) {
                depth--
                if (depth == 0 && i < expr.lastIndex) return false
            }
        }
        return depth == 0
    }

    private fun splitBinaryOp(expr: String, ops: List<String>): Triple<String, String, String>? {
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < expr.length) {
            val ch = expr[i]
            if (quote != null) {
                if (ch == quote) quote = null
                i++
                continue
            }
            if (ch == '"' || ch == '\'') {
                quote = ch
                i++
                continue
            }
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            if (depth == 0) {
                for (op in ops) {
                    if (expr.startsWith(op, i) && i > 0) {
                        val left = expr.substring(0, i).trim()
                        val right = expr.substring(i + op.length).trim()
                        if (left.isNotEmpty() && right.isNotEmpty()) {
                            return Triple(left, op, right)
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    private fun splitBinaryOpRightToLeft(expr: String, ops: List<String>): Triple<String, String, String>? {
        var depth = 0
        var quote: Char? = null
        var matchPos = -1
        var matchOp = ""
        for (i in expr.indices) {
            val ch = expr[i]
            if (quote != null) {
                if (ch == quote) quote = null
                continue
            }
            if (ch == '"' || ch == '\'') {
                quote = ch
                continue
            }
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            if (depth == 0 && i > 0) {
                for (op in ops) {
                    if (expr.startsWith(op, i)) {
                        // Avoid splitting unary minus or ** power
                        val prevNonSpace = expr.substring(0, i).trimEnd().lastOrNull()
                        val nextChar = expr.getOrNull(i + op.length)
                        if (op == "*" && (prevNonSpace == '*' || nextChar == '*')) continue
                        if (op == "-" && (prevNonSpace == null || prevNonSpace in setOf('+', '-', '*', '/', '(', ',', '='))) continue
                        matchPos = i
                        matchOp = op
                    }
                }
            }
        }
        if (matchPos > 0) {
            val left = expr.substring(0, matchPos).trim()
            val right = expr.substring(matchPos + matchOp.length).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) {
                return Triple(left, matchOp, right)
            }
        }
        return null
    }

    private fun isTruthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        else -> true
    }

    private fun toDouble(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().toDoubleOrNull() ?: throw IllegalArgumentException("Cannot convert '$v' to number")
        else -> 0.0
    }

    private fun formatValue(v: Any?): String = when (v) {
        null -> "None"
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        is List<*> -> v.joinToString(prefix = "[", postfix = "]") { formatValue(it) }
        else -> v.toString()
    }
}

/**
 * Section 7 — Workspace-confined Development Scaffolding Toolkit (Python, JS/TS, React, React Native).
 */
object DevProjectToolkit {
    fun scaffoldProject(
        workspaceManager: WorkspaceManager,
        projectName: String,
        template: String
    ): WorkspaceResult {
        val safeName = projectName.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "nayro_project" }
        val normalizedTemplate = template.trim().lowercase()
        val files: Map<String, String> = when (normalizedTemplate) {
            "python", "py" -> mapOf(
                "$safeName/main.py" to "# $safeName (Python)\ndef main():\n    print(\"Hello from $safeName\")\n\nif __name__ == \"__main__\":\n    main()\n",
                "$safeName/README.md" to "# $safeName\nGenerated by NAYRO Dev Toolkit.\n"
            )
            "react", "reactjs" -> mapOf(
                "$safeName/src/App.jsx" to "export default function App() {\n  return <main><h1>$safeName</h1></main>;\n}\n",
                "$safeName/package.json" to "{\n  \"name\": \"$safeName\",\n  \"version\": \"0.1.0\",\n  \"private\": true\n}\n"
            )
            "react-native", "rn" -> mapOf(
                "$safeName/App.tsx" to "import React from 'react';\nimport { SafeAreaView, Text } from 'react-native';\nexport default function App() {\n  return <SafeAreaView><Text>$safeName</Text></SafeAreaView>;\n}\n",
                "$safeName/package.json" to "{\n  \"name\": \"$safeName\",\n  \"version\": \"0.1.0\",\n  \"private\": true\n}\n"
            )
            else -> mapOf(
                "$safeName/index.js" to "// $safeName ($normalizedTemplate)\nconsole.log('Hello from $safeName');\n",
                "$safeName/README.md" to "# $safeName\nGenerated by NAYRO Dev Toolkit.\n"
            )
        }

        val createdPaths = mutableListOf<String>()
        for ((relPath, content) in files) {
            when (val res = workspaceManager.writeFileResult(relPath, content)) {
                is WorkspaceResult.Success -> createdPaths.add(relPath)
                else -> return res
            }
        }
        return WorkspaceResult.Success(
            content = "Scaffolded $normalizedTemplate project '$safeName' inside NAYRO workspace (${createdPaths.size} files: ${createdPaths.joinToString(", ")}).",
            path = safeName
        )
    }
}

/**
 * Section 7 — Structured Data & Spreadsheet (CSV / TSV / JSON Array) Analysis Toolkit.
 */
object StructuredDataToolkit {

    fun analyzeText(rawText: String, formatHint: String = "auto", filterExpr: String? = null): DataAnalysisResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return DataAnalysisResult(false, "unknown", 0, emptyList(), emptyList(), "", "Input data is empty.")
        }
        val useJson = formatHint.equals("json", ignoreCase = true) || (formatHint == "auto" && trimmed.startsWith("["))
        val rows: List<Map<String, String>> = if (useJson) {
            parseSimpleJsonArray(trimmed)
        } else {
            val delimiter = if (formatHint.equals("tsv", ignoreCase = true) || (!trimmed.lines().first().contains(",") && trimmed.lines().first().contains("\t"))) '\t' else ','
            parseDelimitedTable(trimmed, delimiter)
        }

        if (rows.isEmpty()) {
            return DataAnalysisResult(false, if (useJson) "json" else "csv", 0, emptyList(), emptyList(), "", "Could not parse any structured rows.")
        }

        val filteredRows = if (!filterExpr.isNullOrBlank() && filterExpr.contains("=")) {
            val (col, expected) = filterExpr.split("=", limit = 2).map { it.trim() }
            rows.filter { row -> row.entries.any { it.key.equals(col, ignoreCase = true) && it.value.equals(expected, ignoreCase = true) } }
        } else {
            rows
        }

        val columns = rows.flatMap { it.keys }.distinct()
        val stats = columns.map { col ->
            val values = filteredRows.map { it[col].orEmpty().trim() }
            val nonEmpty = values.filter { it.isNotEmpty() }
            val nums = nonEmpty.mapNotNull { it.replace(",", "").toDoubleOrNull() }
            ColumnStats(
                columnName = col,
                totalRows = filteredRows.size,
                nonEmptyCount = nonEmpty.size,
                numericCount = nums.size,
                sum = if (nums.isNotEmpty()) nums.sum() else null,
                avg = if (nums.isNotEmpty()) nums.average() else null,
                min = nums.minOrNull(),
                max = nums.maxOrNull()
            )
        }

        val fmt = if (useJson) "JSON" else "CSV"
        val md = buildString {
            append("### $fmt Data Analysis (${filteredRows.size} rows, ${columns.size} columns)\n")
            if (!filterExpr.isNullOrBlank()) append("Filter applied: `$filterExpr`\n")
            append("| Column | Non-Empty | Sum | Avg | Min | Max |\n")
            append("| --- | ---: | ---: | ---: | ---: | ---: |\n")
            for (s in stats) {
                val sumStr = s.sum?.let { fmtNum(it) } ?: "-"
                val avgStr = s.avg?.let { fmtNum(it) } ?: "-"
                val minStr = s.min?.let { fmtNum(it) } ?: "-"
                val maxStr = s.max?.let { fmtNum(it) } ?: "-"
                append("| ${s.columnName} | ${s.nonEmptyCount}/${s.totalRows} | $sumStr | $avgStr | $minStr | $maxStr |\n")
            }
        }.trim()

        return DataAnalysisResult(
            isSuccess = true,
            format = fmt,
            rowCount = filteredRows.size,
            columns = columns,
            stats = stats,
            reportMarkdown = md
        )
    }

    fun analyzeWorkspaceFile(
        workspaceManager: WorkspaceManager,
        relativePath: String,
        filterExpr: String? = null
    ): DataAnalysisResult {
        val readRes = workspaceManager.readFileResult(relativePath)
        if (readRes !is WorkspaceResult.Success) {
            return DataAnalysisResult(false, "unknown", 0, emptyList(), emptyList(), "", readRes.displayMessage)
        }
        val hint = when {
            relativePath.endsWith(".json", ignoreCase = true) -> "json"
            relativePath.endsWith(".tsv", ignoreCase = true) -> "tsv"
            else -> "csv"
        }
        return analyzeText(readRes.content, formatHint = hint, filterExpr = filterExpr)
    }

    private fun parseDelimitedTable(text: String, delimiter: Char): List<Map<String, String>> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return emptyList()
        val headers = splitCsvLine(lines.first(), delimiter).map { it.trim().removeSurrounding("\"") }
        return lines.drop(1).map { line ->
            val cells = splitCsvLine(line, delimiter)
            headers.mapIndexed { idx, header ->
                header to cells.getOrElse(idx) { "" }.trim().removeSurrounding("\"")
            }.toMap()
        }
    }

    private fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> {
                    cells.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        cells.add(sb.toString())
        return cells
    }

    private fun parseSimpleJsonArray(text: String): List<Map<String, String>> {
        val objectRegex = Regex("\\{([^{}]*)\\}")
        val pairRegex = Regex("\"([^\"]+)\"\\s*:\\s*(?:\"([^\"]*)\"|([-0-9.eE]+|true|false|null))")
        return objectRegex.findAll(text).mapNotNull { match ->
            val pairs = pairRegex.findAll(match.groupValues[1]).associate { m ->
                val key = m.groupValues[1]
                val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
                key to value
            }
            pairs.takeIf { it.isNotEmpty() }
        }.toList()
    }

    private fun fmtNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", v)
}
