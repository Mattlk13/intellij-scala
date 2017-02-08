package org.jetbrains.plugins.scala.testingSupport.specs2.specs2_2_11_3_1M

import com.intellij.openapi.module.Module
import org.jetbrains.plugins.scala.base.libraryLoaders.IvyLibraryLoader.{Bundles, IvyType}
import org.jetbrains.plugins.scala.base.libraryLoaders._
import org.jetbrains.plugins.scala.testingSupport.specs2.Specs2TestCase
import org.jetbrains.plugins.scala.util.TestUtils.ScalaSdkVersion

/**
  * @author Roman.Shein
  * @since 11.01.2015.
  */
trait Specs2_2_11_3_1_M_Base extends Specs2TestCase {

  override protected def additionalLibraries: Seq[ThirdPartyLibraryLoader] = {
    import Specs2_2_11_3_1_M_Base._

    implicit val module = getModule
    Seq(Specs2CommonLoader(), Specs2CoreLoader(), Specs2MatcherLoader(),
      ScalaZCoreLoader(), ScalaZConcurrentLoader(), ScalaZEffectLoader(), ScalaZStreamLoader(),
      ScalaXmlLoader(),
      SCodecBitsLoader(), SCodecCoreLoader())
  }

  override protected val scalaSdkVersion: ScalaSdkVersion = ScalaSdkVersion._2_11

}

object Specs2_2_11_3_1_M_Base {

  abstract class Specs2_3_BaseLoader(implicit module: Module) extends Specs2BaseLoader {
    override protected val version: String = "3.0.1"
  }

  case class Specs2CommonLoader(implicit val module: Module) extends Specs2_3_BaseLoader {
    override protected val name: String = "specs2-common"
  }

  case class Specs2CoreLoader(implicit val module: Module) extends Specs2_3_BaseLoader {
    override protected val name: String = "specs2-core"
  }

  case class Specs2MatcherLoader(implicit val module: Module) extends Specs2_3_BaseLoader {
    override protected val name: String = "specs2-matcher"
  }

  case class ScalaZEffectLoader(implicit val module: Module) extends ScalaZBaseLoader {
    override protected val name: String = "scalaz-effect"
  }

  case class ScalaZStreamLoader(implicit val module: Module) extends ScalaZBaseLoader {
    override protected val name: String = "scalaz-stream"
    override protected val vendor: String = "org.scalaz.stream"
    override protected val version: String = "0.6a"
  }

  case class ShapelessLoader(implicit val module: Module) extends IvyLibraryLoaderAdapter {
    override protected val name: String = "shapeless"
    override protected val vendor: String = "com.chuusai"
    override protected val version: String = "2.0.0"
    override protected val ivyType: IvyType = Bundles
  }

  abstract class SCodecBaseLoader(implicit module: Module) extends IvyLibraryLoaderAdapter {
    override protected val vendor: String = "org.typelevel"
    override protected val ivyType: IvyType = Bundles
  }

  case class SCodecCoreLoader(implicit val module: Module) extends SCodecBaseLoader {
    override protected val name: String = "scodec-core"
    override protected val version: String = "1.7.0-SNAPSHOT"
  }

  case class SCodecBitsLoader(implicit val module: Module) extends SCodecBaseLoader {
    override protected val name: String = "scodec-bits"
    override protected val version: String = "1.1.0-SNAPSHOT"
  }

}