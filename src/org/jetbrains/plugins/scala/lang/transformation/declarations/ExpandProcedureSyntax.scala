package org.jetbrains.plugins.scala.lang.transformation
package declarations

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaCode._

/**
  * @author Pavel Fatin
  */
class ExpandProcedureSyntax extends AbstractTransformer {
  def transformation(implicit project: Project): PartialFunction[PsiElement, Unit] = {
    case (e: ScFunctionDefinition) if !e.hasAssign =>
      val prototype = code"def f(): Unit = ()".asInstanceOf[ScFunctionDefinition]
      val colon = prototype.getParameterList.getNextSibling
      val equals = prototype.assignment.get
      e.addRangeAfter(colon, equals, e.getParameterList)
  }
}
