// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.uast.test.kotlin

import com.intellij.psi.*
import com.intellij.testFramework.LightProjectDescriptor
import junit.framework.TestCase
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.test.KotlinWithJdkAndRuntimeLightProjectDescriptor
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.uast.*
import org.jetbrains.uast.kotlin.KotlinUFunctionCallExpression
import org.jetbrains.uast.test.env.findElementByText
import org.jetbrains.uast.test.env.findElementByTextFromPsi
import org.jetbrains.uast.test.env.findUElementByTextFromPsi
import org.junit.internal.runners.JUnit38ClassRunner
import org.junit.runner.RunWith

@RunWith(JUnit38ClassRunner::class)
class KotlinUastResolveApiTest : KotlinLightCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor =
        KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE


    fun testResolveStringFromUast() {
        val file = myFixture.addFileToProject(
            "s.kt", """fun foo(){
                val s = "abc"
                s.toUpperCase()
                }
            ""${'"'}"""
        )

        val refs = file.findUElementByTextFromPsi<UQualifiedReferenceExpression>("s.toUpperCase()")
        val receiver = refs.receiver
        TestCase.assertEquals(CommonClassNames.JAVA_LANG_STRING, (receiver.getExpressionType() as PsiClassType).resolve()!!.qualifiedName!!)
        val resolve = receiver.cast<UReferenceExpression>().resolve()

        val variable = file.findUElementByTextFromPsi<UVariable>("val s = \"abc\"")
        TestCase.assertEquals(resolve, variable.javaPsi)
        TestCase.assertTrue(
            "resolved expression $resolve should be equivalent to ${variable.sourcePsi}",
            PsiManager.getInstance(project).areElementsEquivalent(resolve, variable.sourcePsi)
        )
    }

    fun testMultiResolve() {
        val file = myFixture.configureByText(
            "s.kt", """
                fun foo(): Int = TODO()
                fun foo(a: Int): Int = TODO()
                fun foo(a: Int, b: Int): Int = TODO()


                fun main(args: Array<String>) {
                    foo(1<caret>
                }"""
        )

        val main = file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
        val functionCall =
            main.findElementByText<UElement>("foo").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { it.element.text ?: "<null>" }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "fun foo(): Int = TODO()",
            "fun foo(a: Int): Int = TODO()",
            "fun foo(a: Int, b: Int): Int = TODO()"
        )

        TestCase.assertEquals(PsiType.INT, functionCall.getExpressionType())

        val firstArgument = main.findElementByText<UElement>("1")
        val firstParameter = functionCall.getArgumentForParameter(0)
        TestCase.assertEquals(firstArgument, firstParameter)

    }

    fun testMultiResolveJava() {
        val file = myFixture.configureByText(
            "s.kt", """

                fun main(args: Array<String>) {
                    System.out.print("1"
                }
                """
        )

        val main = file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
        val functionCall = main.findElementByText<UElement>("print").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { r -> methodSignature(r.element) }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "PsiType:void print(PsiType:boolean)",
            "PsiType:void print(PsiType:char)",
            "PsiType:void print(PsiType:int)",
            "PsiType:void print(PsiType:long)",
            "PsiType:void print(PsiType:float)",
            "PsiType:void print(PsiType:double)",
            "PsiType:void print(PsiType:char[])",
            "PsiType:void print(PsiType:String)",
            "PsiType:void print(PsiType:Object)"
        )

        TestCase.assertEquals(PsiType.VOID, functionCall.getExpressionType())

        val firstArgument = main.findElementByText<UElement>("1")
        val firstParameter = functionCall.getArgumentForParameter(0)
        TestCase.assertEquals(firstArgument, firstParameter)
    }

    fun testMultiResolveJavaAmbiguous() {
        myFixture.addClass(
            """
            public class JavaClass {

                public void setParameter(String name, int value){}
                public void setParameter(String name, double value){}
                public void setParameter(String name, String value){}

            }
        """
        )
        val file = myFixture.configureByText(
            "s.kt", """

                fun main(args: Array<String>) {
                    JavaClass().setParameter("1"
                }
                """
        )

        val main = file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
        val functionCall = main.findElementByText<UElement>("setParameter").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { it.element.text ?: "<null>" }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "public void setParameter(String name, int value){}",
            "public void setParameter(String name, double value){}",
            "public void setParameter(String name, String value){}"

        )

        TestCase.assertEquals(PsiType.VOID, functionCall.getExpressionType())

        val firstArgument = main.findElementByText<UElement>("1")
        val firstParameter = functionCall.getArgumentForParameter(0)
        TestCase.assertEquals(firstArgument, firstParameter)
    }


    fun testResolveFromBaseJava() {
        myFixture.addClass(
            """public class X {
        |native String getFoo();
        |native void setFoo(@org.jetbrains.annotations.Nls String s);
        |}""".trimMargin()
        )
        myFixture.configureByText(
            "Foo.kt", """
               class Foo : X() {
               
                  fun foo(x : X) {
                    foo = "java superclass setter"
                    this.foo = "java superclass qualified setter"
                  }
                }
            """.trimIndent()
        )
        val main = file.toUElement()!!.findElementByTextFromPsi<UElement>("foo").getContainingUMethod()!!
        main.findElementByText<UElement>("foo = \"java superclass setter\"")
            .cast<UBinaryExpression>().leftOperand.cast<UReferenceExpression>().let { assigment1 ->
                val resolvedDeclaration = assigment1.resolve()
                assertEquals("native void setFoo(@org.jetbrains.annotations.Nls String s);", resolvedDeclaration?.text)
            }
        main.findElementByText<UElement>("this.foo = \"java superclass qualified setter\"")
            .cast<UBinaryExpression>().leftOperand.cast<UReferenceExpression>().let { assigment1 ->
                val resolvedDeclaration = assigment1.resolve()
                assertEquals("native void setFoo(@org.jetbrains.annotations.Nls String s);", resolvedDeclaration?.text)
            }

    }

    fun testMultiResolveInClass() {
        val file = myFixture.configureByText(
            "s.kt", """
                class MyClass {

                    fun foo(): Int = TODO()
                    fun foo(a: Int): Int = TODO()
                    fun foo(a: Int, b: Int): Int = TODO()

                }

                fun foo(string: String) = TODO()


                fun main(args: Array<String>) {
                    MyClass().foo(
                }
            """
        )


        val functionCall =
            file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
                .findElementByText<UElement>("foo").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { it.element.text ?: "<null>" }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "fun foo(): Int = TODO()",
            "fun foo(a: Int): Int = TODO()",
            "fun foo(a: Int, b: Int): Int = TODO()"
        )
        assertDoesntContain(resolvedDeclarationsStrings, "fun foo(string: String) = TODO()")
        TestCase.assertEquals(PsiType.INT, functionCall.getExpressionType())
    }

    fun testMultiConstructorResolve() {
        val file = myFixture.configureByText(
            "s.kt", """
                class MyClass(int: Int) {

                    constructor(int: Int, int1: Int) : this(int + int1)

                    fun foo(): Int = TODO()

                }

                fun MyClass(string: String): MyClass = MyClass(1)


                fun main(args: Array<String>) {
                    MyClass(
                }
            """
        )


        val functionCall =
            file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
                .findElementByText<UElement>("MyClass").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { it.element.text ?: "<null>" }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "(int: Int)",
            "constructor(int: Int, int1: Int) : this(int + int1)",
            "fun MyClass(string: String): MyClass = MyClass(1)"
        )
        assertDoesntContain(resolvedDeclarationsStrings, "fun foo(): Int = TODO()")
        TestCase.assertEquals(PsiType.getTypeByName("MyClass", project, file.resolveScope), functionCall.getExpressionType())
    }


    fun testMultiInvokableObjectResolve() {
        val file = myFixture.configureByText(
            "s.kt", """
                object Foo {

                    operator fun invoke(i: Int): Int = TODO()
                    operator fun invoke(i1: Int, i2: Int): Int = TODO()
                    operator fun invoke(i1: Int, i2: Int, i3: Int): Int = TODO()

                }

                fun main(args: Array<String>) {
                    Foo(
                }
            """
        )

        val functionCall =
            file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
                .findElementByText<UElement>("Foo").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { it.element.text ?: "<null>" }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "operator fun invoke(i: Int): Int = TODO()",
            "operator fun invoke(i1: Int, i2: Int): Int = TODO()",
            "operator fun invoke(i1: Int, i2: Int, i3: Int): Int = TODO()"
        )
        TestCase.assertEquals(PsiType.INT, functionCall.getExpressionType())
    }

    fun testMultiResolveJvmOverloads() {
        val file = myFixture.configureByText(
            "s.kt", """

                class MyClass {

                    @JvmOverloads
                    fun foo(i1: Int = 1, i2: Int = 2): Int = TODO()

                }

                fun main(args: Array<String>) {
                    MyClass().foo(
                }"""
        )

        val functionCall =
            file.toUElement()!!.findElementByTextFromPsi<UElement>("main").getContainingUMethod()!!
                .findElementByText<UElement>("foo").uastParent as KotlinUFunctionCallExpression

        val resolvedDeclaration = functionCall.multiResolve()
        val resolvedDeclarationsStrings = resolvedDeclaration.map { it.element.text ?: "<null>" }
        assertContainsElements(
            resolvedDeclarationsStrings,
            "@JvmOverloads\n                    fun foo(i1: Int = 1, i2: Int = 2): Int = TODO()"
        )
        TestCase.assertEquals(PsiType.INT, functionCall.getExpressionType())
    }

    fun testLocalResolve() {
        myFixture.configureByText(
            "MyClass.kt", """
            fun foo() {
                fun bar() {}
                
                ba<caret>r()
            }
        """
        )


        val uCallExpression = myFixture.file.findElementAt(myFixture.caretOffset).toUElement().getUCallExpression().orFail("cant convert to UCallExpression")
        val resolved = uCallExpression.resolve().orFail("cant resolve from $uCallExpression")
        TestCase.assertEquals("bar", resolved.name)
    }


    fun testResolveCompiledAnnotation() {
        myFixture.configureByText(
            "MyClass.kt", """
            @Deprecated(message = "deprecated")    
            fun foo() {}
        """
        )

        val compiledAnnotationParameter = myFixture.file.toUElement()!!.findElementByTextFromPsi<USimpleNameReferenceExpression>("message")
        val resolved = compiledAnnotationParameter.resolve() as PsiMethod
        TestCase.assertEquals("message", resolved.name)
    }

    fun testAssigningArrayElementType() {
        myFixture.configureByText(
            "MyClass.kt", """ 
            fun foo() {
                val arr = arrayOfNulls<List<*>>(10)
                arr[0] = emptyList<Any>()
                
                val lst = mutableListOf<List<*>>()
                lst[0] = emptyList<Any>()
            }
        """
        )

        val uFile = myFixture.file.toUElement()!!

        TestCase.assertEquals(
            "PsiType:List<?>",
            uFile.findElementByTextFromPsi<UExpression>("arr[0]").getExpressionType().toString()
        )
        TestCase.assertEquals(
            "PsiType:List<?>",
            uFile.findElementByTextFromPsi<UExpression>("lst[0]").getExpressionType().toString()
        )
    }
}

private fun methodSignature(psiMethod: PsiMethod): String {
    return "${psiMethod.returnType} ${psiMethod.name}(${psiMethod.parameterList.parameters.joinToString(", ") { it.type.toString() }})"
}

