package com.sleepsentry.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 布局与 Kotlin 字段的类型一致性检查。
 *
 * 这条测试是被一次真机崩溃逼出来的：
 * `legendHost` 在 fragment_calendar.xml 里被误写成 `<TextView>`，
 * 而 Kotlin 声明为 `LinearLayout` 并调用 `view.findViewById(R.id.legendHost)`。
 *
 * **Kotlin 编译器不会报错** —— `findViewById` 返回平台类型（`T!`），
 * 转型发生在运行时，于是一进日历页就 ClassCastException 直接崩溃，
 * 而且是"必崩"—— 只要点日历就关。
 *
 * 编译期看不见、只有装到手机上才炸的 bug，就该在构建期拦住。
 * 这条测试把布局 XML 和 Kotlin 源码一起读，做类型相容性检查。
 */
class LayoutBindingConsistencyTest {

    private fun moduleDir(): File {
        var d = File(".").absoluteFile
        while (d.parentFile != null && !File(d, "build.gradle.kts").exists()) {
            d = d.parentFile
        }
        return d
    }

    private fun layoutDir(): File = File(moduleDir(), "src/main/res/layout")
    private fun sourceDir(): File = File(moduleDir(), "src/main/kotlin/com/sleepsentry")

    private val builtinTags = setOf(
        "TextView", "Button", "Switch", "ImageView", "LinearLayout", "FrameLayout",
        "RelativeLayout", "ConstraintLayout", "View", "WebView", "EditText", "Space",
        "ScrollView", "ViewPager", "ViewPager2", "CheckBox", "RadioButton", "ProgressBar",
        "BottomNavigationView", "FragmentContainerView", "RadioGroup", "ImageButton"
    )

    /** 读布局里的 id → 标签名 */
    private fun idsOf(xml: File): Map<String, String> {
        val s = xml.readText()
        val out = HashMap<String, String>()
        val tagRe = Regex("""<([A-Za-z][\w.]*)\b([^>]*?)/?>""", RegexOption.DOT_MATCHES_ALL)
        for (m in tagRe.findAll(s)) {
            val id = Regex("""android:id="@\+id/(\w+)"""").find(m.groupValues[2]) ?: continue
            out[id.groupValues[1]] = m.groupValues[1].substringAfterLast('.')
        }
        return out
    }

    /** 布局里这个标签能安全赋给哪些声明类型（含父类与常见子类） */
    private fun compatibleWith(tag: String): Set<String> = when (tag) {
        "TextView" -> setOf("TextView", "View", "ViewGroup", "ViewParent")
        "Button" -> setOf("Button", "TextView", "View", "ViewGroup")
        "Switch" -> setOf("Switch", "CompoundButton", "View", "ViewGroup")
        "ImageView" -> setOf("ImageView", "View", "ViewGroup")
        "BottomNavigationView" -> setOf("BottomNavigationView", "NavigationBarView", "ViewGroup", "View")
        "ScrollView" -> setOf("ScrollView", "FrameLayout", "ViewGroup", "View")
        else -> if (tag in builtinTags) setOf(tag, "ViewGroup", "View", "ViewParent")
        else setOf(tag, "View", "ViewGroup", "ViewParent")
    }

    private data class Binding(
        val source: String, val field: String, val declared: String, val rid: String, val tag: String
    )

    /** 扫全部 Kotlin 源码里的 findViewById 赋值，回布局确认标签 */
    private fun collectBindings(): List<Binding> {
        val idIndex = HashMap<String, String>()
        val layoutFiles = layoutDir().listFiles() ?: return emptyList()
        for (lf in layoutFiles) {
            if (!lf.name.endsWith(".xml")) continue
            for ((rid, tag) in idsOf(lf)) idIndex[rid] = tag
        }

        val out = ArrayList<Binding>()
        val ktFiles = sourceDir().walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        for (kf in ktFiles) {
            val code = kf.readText()
            val decls = HashMap<String, String>()
            val declRe = Regex("""private\s+lateinit\s+var\s+(\w+)\s*:\s*([\w.]+)""")
            for (m in declRe.findAll(code)) {
                decls[m.groupValues[1]] = m.groupValues[2].substringAfterLast('.')
            }
            val callRe = Regex("""(\w+)\s*=\s*\w+\.findViewById\(R\.id\.(\w+)\)""")
            for (m in callRe.findAll(code)) {
                val field = m.groupValues[1]
                val rid = m.groupValues[2]
                val declared = decls[field] ?: continue
                val tag = idIndex[rid] ?: continue
                out.add(Binding(kf.name, field, declared, rid, tag))
            }
        }
        return out
    }

    @Test
    fun everyFindViewByIdMatchesItsLayoutTag() {
        val bindings = collectBindings()
        assertTrue("应当至少扫到若干 findViewById 绑定，实际 ${bindings.size}", bindings.isNotEmpty())

        val bad = bindings.filter { it.declared !in compatibleWith(it.tag) }
        if (bad.isNotEmpty()) {
            val detail = bad.joinToString("\n") { b ->
                "  " + b.source + "：" + b.field + " 声明为 " + b.declared +
                    "，但 " + b.rid + " 在布局里是 <" + b.tag + ">" +
                    " → 运行时会抛 ClassCastException，点该页面必崩"
            }
            fail("布局与代码类型不匹配：\n" + detail)
        }
    }

    @Test
    fun everyCustomViewInLayoutIsReferencedByCode() {
        // 反向检查：防止"布局里换了自定义 View，代码里没跟着改"
        val sb = StringBuilder()
        val ktFiles = sourceDir().walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        for (kf in ktFiles) sb.append(kf.readText()).append('\n')
        val code = sb.toString()

        val missing = ArrayList<String>()
        val layoutFiles = layoutDir().listFiles() ?: emptyArray<File>()
        for (lf in layoutFiles) {
            if (!lf.name.endsWith(".xml")) continue
            for ((rid, tag) in idsOf(lf).toList()) {
                if (!tag.first().isUpperCase() || tag in builtinTags) continue
                if (!code.contains(rid) && !code.contains(tag)) {
                    missing.add(lf.name + "：自定义 View " + tag + "（" + rid + "）在代码里找不到")
                }
            }
        }
        assertTrue(
            "以下自定义 View 未被代码引用：\n" + missing.joinToString("\n"),
            missing.isEmpty()
        )
    }

    /** 回归：这次的崩溃点必须被明确钉住 */
    @Test
    fun legendHostMustBeLinearLayoutInCalendarLayout() {
        val f = File(layoutDir(), "fragment_calendar.xml")
        assertTrue("找不到 fragment_calendar.xml", f.exists())
        val tag = idsOf(f)["legendHost"]
        assertTrue(
            "legendHost 在 fragment_calendar.xml 里是 <$tag>，必须是 LinearLayout —— " +
                "Kotlin 侧要往里 addView 图例子项，写成 TextView 会 ClassCastException",
            tag == "LinearLayout"
        )
    }
}