package org.jetbrains.sbt.shell

import java.net.URI
import java.util

import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.{DataNode, ProjectKeys}
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataManager
import com.intellij.openapi.externalSystem.util.{ExternalSystemUtil, ExternalSystemApiUtil => ES}
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.progress.{PerformInBackgroundOption, ProgressIndicator, ProgressManager, Task}
import com.intellij.openapi.project.Project
import com.intellij.task._
import com.intellij.util.BooleanFunction
import org.jetbrains.annotations.Nullable
import org.jetbrains.sbt.project.SbtProjectSystem
import org.jetbrains.sbt.project.data.SbtModuleData
import org.jetbrains.sbt.project.module.SbtModuleType
import org.jetbrains.sbt.settings.SbtSystemSettings
import org.jetbrains.sbt.shell.SbtShellCommunication.{Output, TaskComplete, TaskStart}

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

/**
  * Created by jast on 2016-11-25.
  */
class SbtProjectTaskRunner extends ProjectTaskRunner {

  // FIXME should be based on a config option
  // will override the usual jps build thingies
  override def canRun(projectTask: ProjectTask): Boolean = projectTask match {
    case task: ModuleBuildTask =>
      val module = task.getModule
      ModuleType.get(module) match {
          // TODO Android AARs are currently imported as modules. need a way to filter them away before building
        case _: SbtModuleType =>
          // SbtModuleType actually denotes `-build` modules, which are not part of the regular build
          false
        case _ =>
          val project = task.getModule.getProject
          val projectSettings = SbtSystemSettings.getInstance(project).getLinkedProjectSettings(module)

          projectSettings.exists(_.useSbtShell) &&
          ES.isExternalSystemAwareModule(SbtProjectSystem.Id, module)
      }
    case _: ArtifactBuildTask =>
      // TODO should sbt handle this?
      false
    case _: ExecuteRunConfigurationTask =>
      // TODO this includes tests (and what else?). sbt should handle it and test output should be parsed
      false
    case _ => false
  }

  override def run(project: Project,
                   context: ProjectTaskContext,
                   callback: ProjectTaskNotification,
                   tasks: util.Collection[_ <: ProjectTask]): Unit = {

    val callbackOpt = Option(callback)
    val shell = SbtShellCommunication.forProject(project)

    // the "build" button in IDEA always runs the build for all individual modules,
    // and may work differently than just calling the products task from the main module in sbt
    val moduleCommands = tasks.asScala.collect {
      case task: ModuleBuildTask =>
        val module = task.getModule
        val project = module.getProject
        val moduleId = ES.getExternalProjectId(module) // nullable, but that's okay for use in predicate

        // seems hacky. but it seems there isn't yet any better way to get the data for selected module?
        val predicate = new BooleanFunction[DataNode[ModuleData]] {
          override def fun(s: DataNode[ModuleData]): Boolean = s.getData.getId == moduleId
        }

        val emptyURI = new URI("")
        val dataManager = ProjectDataManager.getInstance()

        // TODO instead of silently not running a task, collect failures, report to user
        for {
          projectInfo <- Option(dataManager.getExternalProjectData(project, SbtProjectSystem.Id, project.getBasePath))
          projectStructure <- Option(projectInfo.getExternalProjectStructure)
          moduleDataNode <- Option(ES.find(projectStructure, ProjectKeys.MODULE, predicate))
          moduleSbtDataNode <- Option(ES.find(moduleDataNode, SbtModuleData.Key))
          data = {
            dataManager.ensureTheDataIsReadyToUse(moduleSbtDataNode)
            moduleSbtDataNode.getData
          }
          // buildURI should never be empty for true sbt projects, but filtering here handles synthetic projects
          // created from AAR files. Should implement a more elegant solution for AARs.
          uri <- Option(data.buildURI) if uri != emptyURI
        } yield {
          val id = data.id
          // `products` task is a little more general than just `compile`
          s"{$uri}$id/products"
        }
        // TODO also run task for non-default scopes? test, it, etc
    }.flatten

    // don't run anything if there's not module to run a build for
    // TODO user feedback
    if (moduleCommands.nonEmpty) {

      val command = moduleCommands.mkString("; ", "; ", "")

      // run this as a task (which blocks a thread) because it seems non-trivial to just update indicators asynchronously?
      val task = new CommandTask(project) {
        override def run(indicator: ProgressIndicator): Unit = {
          indicator.setIndeterminate(true)
          //        indicator.setFraction(0) // TODO how does the fraction thing work? can we also have an indicator without fraction?
          indicator.setText("queued sbt build ...")

          val handler: SbtShellCommunication.EventHandler = {
            case TaskStart =>
              indicator.setIndeterminate(true)
              indicator.setFraction(0.1)
              indicator.setText("building ...")
            case Output(text) =>
              indicator.setText2(text)
            case TaskComplete =>
          }

          // TODO consider running module build tasks separately
          // may require collecting results individually and aggregating
          // and shell communication should do proper queueing
          val commandFuture = shell.commandWithHandler(command, handler)
            .andThen {
              case Success(taskResult) =>
                // TODO progress monitoring
                callbackOpt.foreach(_.finished(taskResult))
                indicator.setFraction(1)
                indicator.setText("sbt build completed")
                indicator.setText2("")
              case Failure(x) =>
                indicator.setText("sbt build failed")
                indicator.setText2(x.getMessage)
              // TODO some kind of feedback / rethrow
            }

          // block thread to make indicator available :(
          Await.ready(commandFuture, Duration.Inf)
        }
      }
      ProgressManager.getInstance().run(task)
    }
  }

  @Nullable
  override def createExecutionEnvironment(project: Project,
                                          task: ExecuteRunConfigurationTask,
                                          executor: Executor): ExecutionEnvironment = {

    val taskSettings = new ExternalSystemTaskExecutionSettings
    val executorId = Option(executor).map(_.getId).getOrElse(DefaultRunExecutor.EXECUTOR_ID)

    ExternalSystemUtil.createExecutionEnvironment(
      project,
      SbtProjectSystem.Id,
      taskSettings, executorId
    )
  }

}

private abstract class CommandTask(project: Project) extends
  Task.Backgroundable(project, "sbt build", false, PerformInBackgroundOption.ALWAYS_BACKGROUND)
