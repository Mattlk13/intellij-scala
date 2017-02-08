package org.jetbrains.plugins.scala
package testingSupport.test

import java.io.{File, FileOutputStream, IOException, PrintStream}
import java.util.regex.{Pattern, PatternSyntaxException}

import com.intellij.diagnostic.logging.LogConfigurationPanel
import com.intellij.execution._
import com.intellij.execution.configurations._
import com.intellij.execution.runners.{ExecutionEnvironment, ProgramRunner}
import com.intellij.execution.testDiscovery.JavaAutoRunManager
import com.intellij.execution.testframework.TestFrameworkRunningModel
import com.intellij.execution.testframework.autotest.{AbstractAutoTestManager, ToggleAutoTestAction}
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.module.{Module, ModuleManager}
import com.intellij.openapi.options.{SettingsEditor, SettingsEditorGroup}
import com.intellij.openapi.project.{DumbService, Project}
import com.intellij.openapi.projectRoots.{JdkUtil, Sdk}
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.util.{Computable, Getter, JDOMExternalizer}
import com.intellij.psi._
import com.intellij.psi.search.{GlobalSearchScope, GlobalSearchScopesCore}
import com.intellij.psi.search.searches.AllClassesSearch
import org.jdom.Element
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.ScPackage
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScModifierListOwner
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScObject, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.psi.impl.{ScPackageImpl, ScalaPsiManager}
import org.jetbrains.plugins.scala.project._
import org.jetbrains.plugins.scala.project.maven.ScalaTestDefaultWorkingDirectoryProvider
import org.jetbrains.plugins.scala.testingSupport.ScalaTestingConfiguration
import org.jetbrains.plugins.scala.testingSupport.locationProvider.ScalaTestLocationProvider
import org.jetbrains.plugins.scala.testingSupport.test.AbstractTestRunConfiguration.PropertiesExtension
import org.jetbrains.plugins.scala.testingSupport.test.TestRunConfigurationForm.{SearchForTest, TestKind}
import org.jetbrains.plugins.scala.testingSupport.test.structureView.TestNodeProvider
import org.jetbrains.plugins.scala.testingSupport.test.utest.UTestConfigurationType
import org.jetbrains.plugins.scala.util.ScalaUtil

import scala.annotation.tailrec
import scala.beans.BeanProperty
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * @author Ksenia.Sautina
 * @since 5/15/12
 */

abstract class AbstractTestRunConfiguration(val project: Project,
                                            val configurationFactory: ConfigurationFactory,
                                            val name: String,
                                            val configurationProducer: TestConfigurationProducer,
                                            private var envs: java.util.Map[String, String] =
                                            new mutable.HashMap[String, String](),
                                            private var addIntegrationTestsClasspath: Boolean = false)
  extends ModuleBasedConfiguration[RunConfigurationModule](name,
    new RunConfigurationModule(project),
    configurationFactory) with ScalaTestingConfiguration {

  val SCALA_HOME = "-Dscala.home="
  val CLASSPATH = "-Denv.classpath=\"%CLASSPATH%\""
  val EMACS = "-Denv.emacs=\"%EMACS%\""

  def setupIntegrationTestClassPath(): Unit = addIntegrationTestsClasspath = true

  def getAdditionalTestParams(testName: String): Seq[String] = Seq()

  def currentConfiguration: AbstractTestRunConfiguration = AbstractTestRunConfiguration.this

  def suitePaths: List[String]

  final def javaSuitePaths: java.util.List[String] = {
    import scala.collection.JavaConverters._
    suitePaths.asJava
  }

  def mainClass: String

  def reporterClass: String

  def errorMessage: String

  private var testClassPath = ""
  private var testPackagePath = ""
  private var testArgs = ""
  private var javaOptions = ""
  private var workingDirectory = ""

  def getTestClassPath: String = testClassPath

  def getTestPackagePath: String = testPackagePath

  def getTestArgs: String = testArgs

  def getJavaOptions: String = javaOptions

  def getWorkingDirectory: String = ExternalizablePath.localPathValue(workingDirectory)

  def setTestClassPath(s: String) {
    testClassPath = s
  }

  def setTestPackagePath(s: String) {
    testPackagePath = s
  }

  def setTestArgs(s: String) {
    testArgs = s
  }

  def setJavaOptions(s: String) {
    javaOptions = s
  }

  def setWorkingDirectory(s: String) {
    workingDirectory = ExternalizablePath.urlValue(s)
  }

  def initWorkingDir(): Unit = if (workingDirectory == null || workingDirectory.trim.isEmpty) setWorkingDirectory(provideDefaultWorkingDir)

  private def provideDefaultWorkingDir = {
    val module = getModule
    ScalaTestDefaultWorkingDirectoryProvider.EP_NAME.getExtensions.find(_.getWorkingDirectory(module) != null) match {
        case Some(provider) => provider.getWorkingDirectory(module)
        case _ => Option(getProject.getBaseDir).map(_.getPath).getOrElse("")
      }
  }

  @BeanProperty
  var searchTest: SearchForTest = SearchForTest.ACCROSS_MODULE_DEPENDENCIES
  @BeanProperty
  var testName = ""
  @BeanProperty
  var testKind = TestKind.CLASS
  @BeanProperty
  var showProgressMessages = true
  @BeanProperty
  var classRegexps: Array[String] = Array.empty
  @BeanProperty
  var testRegexps: Array[String] = Array.empty

  def splitTests: Array[String] = testName.split("\n").filter(!_.isEmpty)

  private var generatedName: String = ""

  def setGeneratedName(name: String) {
    generatedName = name
  }

  override def isGeneratedName: Boolean = getName == null || getName.equals(suggestedName)

  override def suggestedName: String = generatedName

  def apply(configuration: TestRunConfigurationForm) {
    testKind = configuration.getSelectedKind
    setTestClassPath(configuration.getTestClassPath)
    setTestPackagePath(configuration.getTestPackagePath)
    setSearchTest(configuration.getSearchForTest)
    setJavaOptions(configuration.getJavaOptions)
    setTestArgs(configuration.getTestArgs)
    setModule(configuration.getModule)
    val workDir = configuration.getWorkingDirectory
    setWorkingDirectory(
      if (workDir != null && !workDir.trim.isEmpty) {
        workDir
      } else {
        provideDefaultWorkingDir
      }
    )

    setTestName(configuration.getTestName)
    setEnvVariables(configuration.getEnvironmentVariables)
    setShowProgressMessages(configuration.getShowProgressMessages)
    setClassRegexps(configuration.getClassRegexps)
    setTestRegexps(configuration.getTestRegexps)
  }

  def getClazz(path: String, withDependencies: Boolean): PsiClass = {
    val classes = ScalaPsiManager.instance(project).getCachedClasses(getScope(withDependencies), path)
    val objectClasses = classes.filter(_.isInstanceOf[ScObject])
    val nonObjectClasses = classes.filter(!_.isInstanceOf[ScObject])
    if (nonObjectClasses.nonEmpty) nonObjectClasses(0) else if (objectClasses.nonEmpty) objectClasses(0) else null
  }

  def getClassPathClazz: PsiClass = getClazz(getTestClassPath, withDependencies = false)

  def getPackage(path: String): PsiPackage = {
    ScPackageImpl.findPackage(project, path)
  }

  def getScope(withDependencies: Boolean): GlobalSearchScope = {
    def mScope(module: Module) = {
      if (withDependencies) GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
      else GlobalSearchScope.moduleScope(module)
    }
    def unionScope(moduleGuard: Module => Boolean): GlobalSearchScope = {
      var scope: GlobalSearchScope = if (getModule != null) mScope(getModule) else GlobalSearchScope.EMPTY_SCOPE
      for (module <- ModuleManager.getInstance(getProject).getModules) {
        if (moduleGuard(module)) {
          scope = scope.union(mScope(module))
        }
      }
      scope
    }
    testKind match {
      case TestKind.ALL_IN_PACKAGE =>
        searchTest match {
          case SearchForTest.IN_WHOLE_PROJECT => unionScope(_ => true)
          case SearchForTest.IN_SINGLE_MODULE if getModule != null => mScope(getModule)
          case SearchForTest.ACCROSS_MODULE_DEPENDENCIES if getModule != null =>
            unionScope(ModuleManager.getInstance(getProject).isModuleDependent(getModule, _))
          case _ => unionScope(_ => true)
        }
      case _ =>
        if (getModule != null) mScope(getModule)
        else unionScope(_ => true)
    }
  }

  def expandPath(_path: String): String = {
    var path = _path
    path = PathMacroManager.getInstance(project).expandPath(path)
    if (getModule != null) {
      path = PathMacroManager.getInstance(getModule).expandPath(path)
    }
    path
  }

  def getEnvVariables: java.util.Map[String, String] = envs

  def setEnvVariables(variables: java.util.Map[String, String]): Unit = {
    envs = variables
  }

  def getModule: Module = {
    getConfigurationModule.getModule
  }

  def getValidModules: java.util.List[Module] = getProject.modulesWithScala

  override def getModules: Array[Module] = {
    ApplicationManager.getApplication.runReadAction(new Computable[Array[Module]] {
      @SuppressWarnings(Array("ConstantConditions"))
      def compute: Array[Module] = {
        searchTest match {
          case SearchForTest.ACCROSS_MODULE_DEPENDENCIES if getModule != null =>
            val buffer = new ArrayBuffer[Module]()
            buffer += getModule
            for (module <- ModuleManager.getInstance(getProject).getModules) {
              if (ModuleManager.getInstance(getProject).isModuleDependent(getModule, module)) {
                buffer += module
              }
            }
            buffer.toArray
          case SearchForTest.IN_SINGLE_MODULE if getModule != null => Array(getModule)
          case SearchForTest.IN_WHOLE_PROJECT => ModuleManager.getInstance(getProject).getModules
          case _ => Array.empty
        }
      }
    })
  }

  protected def getSuiteClass: PsiClass = {
    val suiteClasses = suitePaths.map(suitePath => getClazz(suitePath, withDependencies = true)).filter(_ != null)

    if (suiteClasses.isEmpty) {
      throw new RuntimeConfigurationException(errorMessage)
    }

    if (suiteClasses.size > 1) {
      throw new RuntimeConfigurationException("Multiple suite traits detected: " + suiteClasses)
    }

    suiteClasses.head
  }

  override def checkConfiguration() {
    super.checkConfiguration()


    def checkModule() = if (getModule == null) throw new RuntimeConfigurationException("Module is not specified")

    testKind match {
      case TestKind.ALL_IN_PACKAGE =>
        searchTest match {
          case SearchForTest.IN_WHOLE_PROJECT =>
          case SearchForTest.IN_SINGLE_MODULE | SearchForTest.ACCROSS_MODULE_DEPENDENCIES => checkModule()
        }
        val pack = JavaPsiFacade.getInstance(project).findPackage(getTestPackagePath)
        if (pack == null) {
          throw new RuntimeConfigurationException("Package doesn't exist")
        }
      case TestKind.CLASS | TestKind.TEST_NAME =>
        checkModule()
        if (getTestClassPath == "") {
          throw new RuntimeConfigurationException("Test Class is not specified")
        }
        val clazz = getClassPathClazz
        if (clazz == null || isInvalidSuite(clazz)) {
          if (clazz != null && !ScalaPsiUtil.cachedDeepIsInheritor(clazz, getSuiteClass)) {
            throw new RuntimeConfigurationException("Class %s is not inheritor of Suite trait".format(getTestClassPath))
          } else {
            throw new RuntimeConfigurationException("No Suite Class is found for Class %s in module %s".
              format(getTestClassPath,
              getModule.getName))
          }
        }
        if (testKind == TestKind.TEST_NAME && getTestName == "") {
          throw new RuntimeConfigurationException("Test Name is not specified")
        }
      case TestKind.REGEXP =>
        checkModule()
        checkRegexps((e, p) => new RuntimeConfigurationException(s"Failed to compile pattern $p"), new RuntimeConfigurationException("No patterns detected"))
    }

    JavaRunConfigurationExtensionManager.checkConfigurationIsValid(this)
  }

  def getConfigurationEditor: SettingsEditor[_ <: RunConfiguration] = {
    val group: SettingsEditorGroup[AbstractTestRunConfiguration] = new SettingsEditorGroup
    group.addEditor(ExecutionBundle.message("run.configuration.configuration.tab.title"),
      new AbstractTestRunConfigurationEditor(project, this))
    JavaRunConfigurationExtensionManager.getInstance.appendEditors(this, group)
    group.addEditor(ExecutionBundle.message("logs.tab.title"), new LogConfigurationPanel)
    group
  }

  protected[test] def isInvalidSuite(clazz: PsiClass): Boolean = AbstractTestRunConfiguration.isInvalidSuite(clazz, getSuiteClass)

  protected def zippedRegexps: Array[(String, String)] = getClassRegexps.zipAll(getTestRegexps, "", "")

  protected def checkRegexps(compileException: (PatternSyntaxException, String) => Exception, noPatternException: Exception): Unit = {
    val patterns = zippedRegexps
    if (patterns.isEmpty) throw noPatternException
    for ((classString, testString) <- patterns) {
      try {
        Pattern.compile(classString)
      } catch {
        case e: PatternSyntaxException => throw compileException(e, classString)
      }
      try {
        Pattern.compile(testString)
      } catch {
        case e: PatternSyntaxException => throw compileException(e, classString)
      }
    }
  }

  protected def findTestsByFqnCondition(classCondition: String => Boolean, testCondition: String => Boolean,
                                        classToTests: mutable.Map[String, Set[String]]): Unit = {
    val suiteClasses = AllClassesSearch.search(getSearchScope.intersectWith(GlobalSearchScopesCore.projectTestScope(project)),
      project).filter(c => classCondition(c.qualifiedName)).filterNot(isInvalidSuite)
    //we don't care about linearization here, so can process in arbitrary order
    @tailrec
    def getTestNames(classesToVisit: List[ScTypeDefinition], visited: Set[ScTypeDefinition] = Set.empty,
                     res: Set[String] = Set.empty): Set[String] = {
      if (classesToVisit.isEmpty) res
      else if (visited.contains(classesToVisit.head)) getTestNames(classesToVisit.tail, visited, res)
      else {
        getTestNames(classesToVisit.head.supers.toList.filter(_.isInstanceOf[ScTypeDefinition]).
          map(_.asInstanceOf[ScTypeDefinition]).filter(!visited.contains(_)) ++ classesToVisit.tail,
          visited + classesToVisit.head,
          res ++ TestNodeProvider.getTestNames(classesToVisit.head, configurationProducer))
      }
    }

    suiteClasses.map {
      case aSuite: ScTypeDefinition =>
        classToTests += (aSuite.qualifiedName -> getTestNames(List(aSuite)).filter(testCondition))
      case _ => None
    }
  }

  protected def addRegexps(add: String => Unit) {
    val patterns = zippedRegexps
    val classToTests = mutable.Map[String, Set[String]]()
    if (DumbService.getInstance(project).isDumb)
      throw new ExecutionException("Can't run pattern run configurations while indexes are updating ")

    def getCondition(patternString: String): String => Boolean = {
      try {
        val pattern = Pattern.compile(patternString)
        (input: String) => input != null && (pattern.matcher(input).matches ||
          input.startsWith("_root_.") && pattern.matcher(input.substring("_root_.".length)).matches)
      } catch {
        case e: PatternSyntaxException =>
          throw new ExecutionException(s"Failed to compile pattern $patternString", e)
      }
    }

    patterns foreach {
      case ("", "") => //do nothing, empty patterns are ignored
      case ("", testPatternString) => //run all tests with names matching the pattern
        findTestsByFqnCondition(_ => true, getCondition(testPatternString), classToTests)
      case (classPatternString, "") => //run all tests for all classes matching the pattern
        findTestsByFqnCondition(getCondition(classPatternString), _ => true, classToTests)
      case (classPatternString, testPatternString) => //the usual case
        findTestsByFqnCondition(getCondition(classPatternString), getCondition(testPatternString), classToTests)
    }
    for ((className, tests) <- classToTests) {
      if (tests.nonEmpty) {
        add("-s")
        add(className)
      }
      for (test <- tests) {
        add("-testName")
        add(test)
      }
    }
  }

  override def getState(executor: Executor, env: ExecutionEnvironment): RunProfileState = {
    def classNotFoundError() {
      throw new ExecutionException("Test class not found.")
    }
    var clazz: PsiClass = null
    var suiteClass: PsiClass = null
    var pack: ScPackage = null
    try {
      testKind match {
        case TestKind.CLASS =>
          clazz = getClassPathClazz
        case TestKind.ALL_IN_PACKAGE =>
          pack = ScPackageImpl(getPackage(getTestPackagePath))
        case TestKind.TEST_NAME =>
          clazz = getClassPathClazz
          if (getTestName == null || getTestName == "") throw new ExecutionException("Test name not found.")
        case TestKind.REGEXP =>
          checkRegexps((e, p) => new ExecutionException(s"Failed to compile pattern $p", e), new ExecutionException("No patterns detected"))
      }
      suiteClass = getSuiteClass
    }
    catch {
      case _ if clazz == null => classNotFoundError()
    }
    if (clazz == null && pack == null && testKind != TestKind.REGEXP) classNotFoundError()
    if (suiteClass == null)
      throw new ExecutionException(errorMessage)
    val classes = new mutable.HashSet[PsiClass]
    testKind match {
      case TestKind.CLASS | TestKind.TEST_NAME =>
        //requires explicitly state class
        if (ScalaPsiUtil.cachedDeepIsInheritor(clazz, suiteClass)) classes += clazz else throw new ExecutionException("Not found suite class.")
      case TestKind.ALL_IN_PACKAGE =>
        val scope = getScope(withDependencies = false)
        def getClasses(pack: ScPackage): Seq[PsiClass] = {
          val buffer = new ArrayBuffer[PsiClass]

          buffer ++= pack.getClasses(scope)
          for (p <- pack.getSubPackages) {
            buffer ++= getClasses(ScPackageImpl(p))
          }
          if (configurationFactory.getType.isInstanceOf[UTestConfigurationType])
            buffer.filter {_.isInstanceOf[ScObject]}
          else buffer
        }
        for (cl <- getClasses(pack)) {
          if (!isInvalidSuite(cl))
            classes += cl
        }
        if (classes.isEmpty) throw new ExecutionException("Not found suite class.")
      case TestKind.REGEXP =>
        //actuall addition of suites and tests happens in addPatterns()
    }

    val module = getModule
    if (module == null) throw new ExecutionException("Module is not specified")

    val state = new JavaCommandLineState(env) with AbstractTestRunConfiguration.TestCommandLinePatcher {
      val getClasses: Seq[String] = getClassFileNames(classes)

      protected override def createJavaParameters: JavaParameters = {
        val params = new JavaParameters()

        params.setCharset(null)
        var vmParams = getJavaOptions

        //expand macros
        vmParams = PathMacroManager.getInstance(project).expandPath(vmParams)

        if (module != null) {
          vmParams = PathMacroManager.getInstance(module).expandPath(vmParams)
        }

        val mutableEnvVariables = new mutable.HashMap[String, String]() ++ getEnvVariables
        params.setEnv(mutableEnvVariables)

        //expand environment variables in vmParams
        for (entry <- params.getEnv.entrySet) {
          vmParams = StringUtil.replace(vmParams, "$" + entry.getKey + "$", entry.getValue, false)
        }

        params.getVMParametersList.addParametersString(vmParams)
        val wDir = getWorkingDirectory
        params.setWorkingDirectory(expandPath(wDir))

//        params.getVMParametersList.addParametersString("-Xnoagent -Djava.compiler=NONE -Xdebug " +
//          "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5010")

        val rtJarPath = ScalaUtil.runnersPath()
        params.getClassPath.add(rtJarPath)
        if (addIntegrationTestsClasspath) {
          //a workaround to add jars for integration tests
          val integrationTestsPath = ScalaUtil.testingSupportTestPath()
          params.getClassPath.add(integrationTestsPath)
        }

        searchTest match {
          case SearchForTest.IN_WHOLE_PROJECT =>
            var jdk: Sdk = null
            for (module <- ModuleManager.getInstance(project).getModules if jdk == null) {
              jdk = JavaParameters.getValidJdkToRunModule(module, false)
            }
            params.configureByProject(project, JavaParameters.JDK_AND_CLASSES_AND_TESTS, jdk)
          case _ =>
            params.configureByModule(module, JavaParameters.JDK_AND_CLASSES_AND_TESTS, JavaParameters.getValidJdkToRunModule(module, false))
        }

        params.setMainClass(mainClass)

        def addParameters(add: String => Unit) = {
          if (getFailedTests == null) {
            if (testKind == TestKind.REGEXP) {
              addRegexps(add)
            } else {
              add("-s")
              for (cl <- getClasses) {
                add(cl)
              }
              if (testKind == TestKind.TEST_NAME && testName != "") {
                //this is a "by-name" test for single suite, better fail in a known manner then do something undefined
                assert(getClasses.size == 1)
                for (test <- splitTests) {
                  add("-testName")
                  add(test)
                  for (testParam <- getAdditionalTestParams(test)) {
                    params.getVMParametersList.addParametersString(testParam)
                  }
                }
              }
            }
          } else {
            add("-failedTests")
            for (failed <- getFailedTests) {
              add(failed._1)
              add(failed._2)
              for (testParam <- getAdditionalTestParams(failed._2)) {
                params.getVMParametersList.addParametersString(testParam)
              }
            }
          }
          add("-showProgressMessages")
          add(showProgressMessages.toString)
          if (reporterClass != null) {
            add("-C")
            add(reporterClass)
          }
        }

        if (JdkUtil.useDynamicClasspath(getProject)) {
          try {
            val fileWithParams: File = File.createTempFile("abstracttest", ".tmp")
            val outputStream = new FileOutputStream(fileWithParams)
            val printer: PrintStream = new PrintStream(outputStream)
            addParameters(printer.println)

            val parms: Array[String] = ParametersList.parse(getTestArgs)
            for (parm <- parms) {
              printer.println(parm)
            }

            printer.close()
            params.getProgramParametersList.add("@" + fileWithParams.getPath)
          }
          catch {
            case ioException: IOException => throw new ExecutionException("Failed to create dynamic classpath file with command-line args.", ioException)
          }
        } else {
          addParameters(params.getProgramParametersList.add)
          params.getProgramParametersList.addParametersString(getTestArgs)
        }

        for (ext <- Extensions.getExtensions(RunConfigurationExtension.EP_NAME)) {
          ext.updateJavaParameters(currentConfiguration, params, getRunnerSettings)
        }

        params
      }

      override def execute(executor: Executor, runner: ProgramRunner[_ <: RunnerSettings]): ExecutionResult = {
        val processHandler = startProcess
        val runnerSettings = getRunnerSettings
        if (getConfiguration == null) setConfiguration(currentConfiguration)
        val config = getConfiguration
        JavaRunConfigurationExtensionManager.getInstance.
          attachExtensionsToProcess(currentConfiguration, processHandler, runnerSettings)
        val consoleProperties = new SMTRunnerConsoleProperties(currentConfiguration, "Scala", executor)
          with PropertiesExtension {
            override def getTestLocator = new ScalaTestLocationProvider
            def getRunConfigurationBase: RunConfigurationBase = config
        }

        consoleProperties.setIdBasedTestTree(true)

        // console view
        val consoleView = SMTestRunnerConnectionUtil.createAndAttachConsole("Scala", processHandler, consoleProperties)

        val res = new DefaultExecutionResult(consoleView, processHandler,
          createActions(consoleView, processHandler, executor): _*)

        val rerunFailedTestsAction = new AbstractTestRerunFailedTestsAction(consoleView)
        rerunFailedTestsAction.init(consoleView.getProperties)
        rerunFailedTestsAction.setModelProvider(new Getter[TestFrameworkRunningModel] {
          def get: TestFrameworkRunningModel = {
            consoleView.asInstanceOf[SMTRunnerConsoleView].getResultsViewer
          }
        })
        res.setRestartActions(rerunFailedTestsAction, new ToggleAutoTestAction () {
          override def isDelayApplicable: Boolean = false

          override def getAutoTestManager(project: Project): AbstractAutoTestManager = JavaAutoRunManager.getInstance(project)
        })
        res
      }
    }
    state
  }

  protected def getClassFileNames(classes: mutable.HashSet[PsiClass]): Seq[String] = classes.map(_.qualifiedName).toSeq

  override def writeExternal(element: Element) {
    super.writeExternal(element)
    JavaRunConfigurationExtensionManager.getInstance.writeExternal(this, element)
    writeModule(element)
    JDOMExternalizer.write(element, "path", getTestClassPath)
    JDOMExternalizer.write(element, "package", getTestPackagePath)
    JDOMExternalizer.write(element, "vmparams", getJavaOptions)
    JDOMExternalizer.write(element, "params", getTestArgs)
    JDOMExternalizer.write(element, "workingDirectory", workingDirectory)
    JDOMExternalizer.write(element, "searchForTest", searchTest.toString)
    JDOMExternalizer.write(element, "testName", testName)
    JDOMExternalizer.write(element, "testKind", if (testKind != null) testKind.toString else TestKind.CLASS.toString)
    JDOMExternalizer.write(element, "showProgressMessages", showProgressMessages.toString)
    val classRegexps: Map[String, String] = Map(zippedRegexps.zipWithIndex.map{case ((c, _), i) => (i.toString, c)}:_*)
    val testRegexps: Map[String, String] = Map(zippedRegexps.zipWithIndex.map{case ((_, t), i) => (i.toString, t)}:_*)
    JDOMExternalizer.writeMap(element, classRegexps, "classRegexps", "pattern")
    JDOMExternalizer.writeMap(element, testRegexps, "testRegexps", "pattern")
    JDOMExternalizer.writeMap(element, envs, "envs", "envVar")
    PathMacroManager.getInstance(getProject).collapsePathsRecursively(element)
  }

  override def readExternal(element: Element) {
    def loadRegexps(pMap: mutable.Map[String, String]): Array[String] = {
      val res = new Array[String](pMap.size)
      pMap.foreach{case (index, pattern) => res(Integer.parseInt(index)) = pattern}
      res
    }
    PathMacroManager.getInstance(getProject).expandPaths(element)
    super.readExternal(element)
    JavaRunConfigurationExtensionManager.getInstance.readExternal(this, element)
    readModule(element)
    testClassPath = JDOMExternalizer.readString(element, "path")
    testPackagePath = JDOMExternalizer.readString(element, "package")
    javaOptions = JDOMExternalizer.readString(element, "vmparams")
    testArgs = JDOMExternalizer.readString(element, "params")
    workingDirectory = JDOMExternalizer.readString(element, "workingDirectory")
    val classRegexpsMap = mutable.Map[String, String]()
    JDOMExternalizer.readMap(element, classRegexpsMap, "classRegexps", "pattern")
    classRegexps = loadRegexps(classRegexpsMap)
    val testRegexpssMap = mutable.Map[String, String]()
    JDOMExternalizer.readMap(element, testRegexpssMap, "testRegexps", "pattern")
    testRegexps = loadRegexps(testRegexpssMap)
    JDOMExternalizer.readMap(element, envs, "envs", "envVar")
    val s = JDOMExternalizer.readString(element, "searchForTest")
    for (search <- SearchForTest.values()) {
      if (search.toString == s) searchTest = search
    }
    testName = Option(JDOMExternalizer.readString(element, "testName")).getOrElse("")
    testKind = TestKind.fromString(Option(JDOMExternalizer.readString(element, "testKind")).getOrElse("Class"))
    showProgressMessages = JDOMExternalizer.readBoolean(element, "showProgressMessages")
  }
}

trait SuiteValidityChecker {
  protected[test] def isInvalidSuite(clazz: PsiClass, suiteClass: PsiClass): Boolean = {
    !clazz.isInstanceOf[ScClass] || {
      val list: PsiModifierList = clazz.getModifierList
      list != null && list.hasModifierProperty(PsiModifier.ABSTRACT) || lackSuitableConstructor(clazz)
    } || !ScalaPsiUtil.cachedDeepIsInheritor(clazz, suiteClass)
  }

  protected[test] def lackSuitableConstructor(clazz: PsiClass): Boolean
}

object AbstractTestRunConfiguration extends SuiteValidityChecker {

  private[test] trait TestCommandLinePatcher {
    private var failedTests: Seq[(String, String)] = _

    def setFailedTests(failedTests: Seq[(String, String)]) {
      this.failedTests = Option(failedTests).map(_.distinct).orNull
    }

    def getFailedTests: Seq[(String, String)] = failedTests

    def getClasses: Seq[String]

    @BeanProperty
    var configuration: RunConfigurationBase = _
  }

  private[test] trait PropertiesExtension extends SMTRunnerConsoleProperties{
    def getRunConfigurationBase: RunConfigurationBase
  }

  protected[test] def lackSuitableConstructor(clazz: PsiClass): Boolean = {
    val constructors = clazz match {
      case c: ScClass => c.secondaryConstructors.filter(_.isConstructor).toList ::: c.constructor.toList
      case _ => clazz.getConstructors.toList
    }
    for (con <- constructors) {
      if (con.isConstructor && con.getParameterList.getParametersCount == 0) {
        con match {
          case owner: ScModifierListOwner =>
            if (owner.hasModifierProperty(PsiModifier.PUBLIC)) return false
          case _ =>
        }
      }
    }
    true
  }
}
