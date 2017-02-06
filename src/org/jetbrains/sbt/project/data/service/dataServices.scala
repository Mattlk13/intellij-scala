package org.jetbrains.sbt.project.data.service

import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.{DataNode, Key}
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.jetbrains.sbt.project.data.{SbtModuleData, SbtSettingData, SbtTaskData}

/**
  * Created by jast on 2017-01-24.
  */
class SbtModuleDataService extends DefaultDataService[SbtModuleData, Module](SbtModuleData.Key)

class SbtTaskDataService extends DefaultDataService[SbtTaskData, Module](SbtTaskData.Key)

class SbtSettingDataService extends DefaultDataService[SbtSettingData, Module](SbtSettingData.Key)


abstract class DefaultDataService[E,I](key: Key[E]) extends AbstractDataService[E,I](key) {
  override def createImporter(toImport: Seq[DataNode[E]],
                              projectData: ProjectData,
                              project: Project,
                              modelsProvider: IdeModifiableModelsProvider): Importer[E] =

    new AbstractImporter[E](toImport, projectData, project, modelsProvider) {
      override def importData(): Unit = ()
    }
}