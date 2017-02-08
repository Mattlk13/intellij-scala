package org.jetbrains.plugins.scala
package findUsages

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi._
import com.intellij.psi.search._
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import org.jetbrains.annotations.{NotNull, Nullable}
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.impl.ScPackageImpl


class ScalaPackageUsagesSearcher extends QueryExecutorBase[PsiReference, ReferencesSearch.SearchParameters](true) {

  def processQuery(@NotNull parameters: ReferencesSearch.SearchParameters, @NotNull consumer: Processor[PsiReference]) {
    val data = inReadAction {
      parameters.getElementToSearch match {
        case pack: PsiPackage =>
          val scPack =ScPackageImpl(pack)
          val nm = scPack.name
          if (nm != null && !StringUtil.isEmptyOrSpaces(nm))
            Some((scPack, nm, parameters.getEffectiveSearchScope))
          else None
        case _ => None
      }
    }
    data match {
      case Some((scPack, name, scope)) =>
        val collector: SearchRequestCollector = parameters.getOptimizer
        val session: SearchSession = collector.getSearchSession
        collector.searchWord(name, scope, UsageSearchContext.IN_CODE, true, new MyProcessor(scPack, null, session))
      case _ =>
    }
  }

  private class MyProcessor(myTarget: PsiElement, @Nullable prefix: String, mySession: SearchSession) extends RequestResultProcessor(myTarget, prefix) {
    def processTextOccurrence(element: PsiElement, offsetInElement: Int, consumer: Processor[PsiReference]): Boolean = inReadAction {
      val reference: PsiReference = element.getReference
      if (reference == null || !reference.isReferenceTo(myTarget)) {
        true
      } else consumer.process(reference)
    }
  }
}
