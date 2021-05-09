/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections.lints

import org.rust.ide.inspections.RsInspectionsTestBase

class RsNonShorthandFieldPatternsInspectionTest : RsInspectionsTestBase(RsNonShorthandFieldPatternsInspection::class) {
    fun `test not applicable`() = checkFixIsUnavailable("Use shorthand field pattern: `foo`", """
        fn main() {
            match foo {
                S { foo: bar<caret>, baz: &baz } => (),
            }

        }
    """, checkWeakWarn = true)

    fun `test fix`() = checkFixByText("Use shorthand field pattern: `foo`", """
        fn main() {
            match foo {
                S { <weak_warning descr="The `foo:` in this pattern is redundant">foo: foo<caret></weak_warning>, baz: quux } => (),
            }
        }
    """, """
        fn main() {
            match foo {
                S { foo<caret>, baz: quux } => (),
            }
        }
    """, checkWeakWarn = true)
}
