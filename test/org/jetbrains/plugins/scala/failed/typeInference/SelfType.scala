package org.jetbrains.plugins.scala.failed.typeInference

import org.jetbrains.plugins.scala.PerfCycleTests
import org.jetbrains.plugins.scala.lang.typeInference.TypeInferenceTestBase
import org.junit.experimental.categories.Category

/**
  * Created by kate on 3/23/16.
  */
@Category(Array(classOf[PerfCycleTests]))
class SelfType extends TypeInferenceTestBase {
  override def folderPath: String = super.folderPath + "bugs5/"

  def testSCL5947(): Unit = doTest()

}
