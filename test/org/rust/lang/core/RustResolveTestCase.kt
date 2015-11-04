package org.rust.lang.core

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import org.rust.lang.RustTestCase
import org.rust.lang.core.psi.RustNamedElement
import org.assertj.core.api.Assertions.assertThat

class RustResolveTestCase : RustTestCase() {
    override fun getTestDataPath() = "testData/resolve"
    private fun referenceAtCaret() = file.findReferenceAt(myFixture.caretOffset)!!

    //@formatter:off
    fun testFunctionArgument()     { checkIsBound()   }
    fun testLocals()               { checkIsBound()   }
    fun testShadowing()            { checkIsBound(atOffset = 35) }
    fun testNestedPatterns()       { checkIsBound()   }
    fun testClosure()              { checkIsBound()   }
    fun testMatch()                { checkIsBound()   }
    fun testIfLet()                { checkIsBound()   }
    fun testIfLetX()               { checkIsUnbound() }
    fun testWhileLet()             { checkIsBound()   }
    fun testWhileLetX()            { checkIsUnbound() }
    fun testFor()                  { checkIsBound()   }
    fun testTraitMethodArgument()  { checkIsBound()   }
    fun testImplMethodArgument()   { checkIsBound()   }
    fun testStructPatterns1()      { checkIsBound(atOffset = 69) }
    fun testStructPatterns2()      { checkIsBound()   }
    fun testModItems()             { checkIsBound()   }
    fun testNestedModule()         { checkIsBound(atOffset = 48) }
    fun testUnbound()              { checkIsUnbound() }
    fun testOrdering()             { checkIsUnbound() }
    //@formatter:on

    private fun assertIsValidDeclaration(declaration: PsiElement, usage: PsiReference,
                                         expectedOffset: Int?) {

        assertThat(declaration).isInstanceOf(RustNamedElement::class.java)
        declaration as RustNamedElement


        if (expectedOffset != null) {
            assertThat(declaration.textOffset).isEqualTo(expectedOffset)
        } else {
            assertThat(declaration.name).isEqualTo(usage.canonicalText)
        }
    }

    private fun checkIsBound(atOffset: Int? = null) {
        myFixture.configureByFile(fileName)

        val usage = referenceAtCaret()
        val declaration = usage.resolve()!!

        assertIsValidDeclaration(declaration, usage, atOffset)
    }

    private fun checkIsUnbound() {
        myFixture.configureByFile(fileName)

        val usage = referenceAtCaret()
        val declaration = usage.resolve()

        assertThat(declaration).isNull()
    }
}
