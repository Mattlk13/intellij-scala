package org.jetbrains.plugins.scala.codeInspection.collections

import org.jetbrains.plugins.scala.codeInspection.InspectionBundle
import org.jetbrains.plugins.scala.extensions.ResolvesTo
import org.jetbrains.plugins.scala.lang.psi.api.expr._

/**
  * @author Lukasz Piepiora
  */
class EmulateFlattenInspection extends OperationOnCollectionInspection {
  override def possibleSimplificationTypes: Array[SimplificationType] =
    Array(FlattenSimplification)
}

object FlattenSimplification extends SimplificationType {

  override def hint: String = InspectionBundle.message("replace.with.flatten")

  override def getSimplification(expr: ScExpression): Option[Simplification] =
    expr match {
      case seqOfSeqs `.flatMap` (identityOperation()) =>
        Some(replace(expr).withText(invocationText(seqOfSeqs, "flatten")))
      case _ => None
    }

  private object identityOperation {
    def unapply(expr: ScExpression): Boolean = stripped(expr) match {
      case identity(underscore()) => true
      case identity() => true
      case undSect: ScUnderscoreSection =>
        undSect.bindingExpr match {
          case Some(identity()) => true
          case _ => false
        }
      case ScFunctionExpr(Seq(x), Some(ResolvesTo(param))) if x == param => true
      case ScFunctionExpr(Seq(x), Some(identity(ResolvesTo(param)))) if x == param => true
      case _ => false
    }
  }

  object identity {
    private val qualIdentity = invocation("identity").from(Array("scala.Predef"))
    private val unqualIdentity = unqualifed("identity").from(Array("scala.Predef"))

    def unapplySeq(expr: ScExpression): Option[Seq[ScExpression]] = expr match {
      case _ qualIdentity(arg) => Some(Seq(arg))
      case _ qualIdentity() => Some(Nil)
      case unqualIdentity(arg) => Some(Seq(arg))
      case unqualIdentity() => Some(Nil)
      case _ => None
    }
  }

}