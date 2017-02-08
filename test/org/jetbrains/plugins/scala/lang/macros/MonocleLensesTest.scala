package org.jetbrains.plugins.scala.lang.macros

import com.intellij.openapi.module.Module
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.base.ScalaLightPlatformCodeInsightTestCaseAdapter
import org.jetbrains.plugins.scala.base.libraryLoaders.{IvyLibraryLoaderAdapter, ThirdPartyLibraryLoader}
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiElement
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject
import org.jetbrains.plugins.scala.lang.psi.types.result.{Failure, Success}
import org.jetbrains.plugins.scala.util.TestUtils
import org.jetbrains.plugins.scala.util.TestUtils.ScalaSdkVersion
import org.junit.Assert._

class MonocleLensesTest extends ScalaLightPlatformCodeInsightTestCaseAdapter {

  override protected def getDefaultScalaSDKVersion: ScalaSdkVersion = ScalaSdkVersion._2_11

  override protected def additionalLibraries(module: Module): Array[ThirdPartyLibraryLoader] = {
    import MonocleLensesTest._

    implicit val module = getModuleAdapter
    Array(MonocleCoreLoader(), MonocleMacroLoader(), MonocleGeneric())
  }

  protected def folderPath: String = TestUtils.getTestDataPath

  def doTest(text: String, methodName: String, expectedType: String) = {
    val caretPos = text.indexOf("<caret>")
    configureFromFileTextAdapter("dummy.scala", text.replace("<caret>", ""))
    val exp = PsiTreeUtil.findElementOfClassAtOffset(getFileAdapter, caretPos, classOf[ScalaPsiElement], false).asInstanceOf[ScObject]
    exp.allMethods.find(_.name == methodName) match {
      case Some(x) => x.method.asInstanceOf[ScFunctionDefinition].returnType match {
        case Success(t, _) => assertEquals(s"${t.toString} != $expectedType", expectedType, t.toString)
        case Failure(cause, _) => fail(cause)
      }
      case None => fail("method not found")
    }
  }

  def testSimple() = {
    val fileText: String =
      """
        |import monocle.macros.Lenses
        |
        |object Main {
        |  @Lenses
        |  case class Person(name: String, age: Int, address: Address)
        |  @Lenses
        |  case class Address(streetNumber: Int, streetName: String)
        |
        |  object <caret>Person {
        |    import Main.Address._
        |    val john = Person("John", 23, Address(10, "High Street"))
        |    age.get(john)
        |  }
        |}
      """.stripMargin

    doTest(fileText, "age", "monocle.Lens[Main.Person, Int]")
  }

  def testTypeArgs() = {
    val fileText =
      """
        |import monocle.macros.Lenses
        |import monocle.syntax._
        |
        |object Main {
        |
        |  @Lenses
        |  case class Foo[A,B](q: Map[(A,B),Double], default: Double)
        |  object <caret>Foo {}
        |}
      """.stripMargin

    doTest(fileText, "q", "monocle.Lens[Main.Foo[A, B], Map[(A, B), Double]]")
  }

  def testRecursion() = {
    //SCL-9420
    val fileText =
      """
        |object Main {
        |import monocle.macros.Lenses
        |import A.B
        |
        |object <caret>A {
        |  type B = String
        |}
        |
        |@Lenses
        |case class A(s : B) {
        |  def blah = s.getBytes
        |}
        |}
      """.stripMargin

    doTest(fileText, "s", "monocle.Lens[Main.A, Main.A.B]")
  }
}

object MonocleLensesTest {

  private abstract class MonocleBaseLoader(implicit module: Module) extends IvyLibraryLoaderAdapter {
    override protected val version: String = "1.2.0"
    override protected val vendor: String = "com.github.julien-truffaut"
  }

  private case class MonocleCoreLoader(implicit val module: Module) extends MonocleBaseLoader {
    override protected val name: String = "monocle-core"
  }

  private case class MonocleMacroLoader(implicit val module: Module) extends MonocleBaseLoader {
    override protected val name: String = "monocle-macro"
  }

  private case class MonocleGeneric(implicit val module: Module) extends MonocleBaseLoader {
    override protected val name: String = "monocle-generic"
  }

}
