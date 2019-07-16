package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import java.lang.ProcessBuilder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.internal.builds.Digest
import scala.meta.internal.builds.Digest.Status
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.util.Success

/**
 * Runs `sbt/gradle bloopInstall` processes.
 *
 * Handles responsibilities like:
 * - install metals
 * - launching embedded build tool via system process
 * - reporting client about `bloopInstall` progress
 */
final class BloopInstall(
    workspace: AbsolutePath,
    languageClient: MetalsLanguageClient,
    sh: ScheduledExecutorService,
    buildTools: BuildTools,
    time: Time,
    tables: Tables,
    messages: Messages,
    config: MetalsServerConfig,
    embedded: Embedded,
    statusBar: StatusBar,
    userConfig: () => UserConfiguration
)(implicit ec: ExecutionContext)
    extends Cancelable {
  import messages._
  private val cancelables = new MutableCancelable()
  override def cancel(): Unit = {
    cancelables.cancel()
  }

  override def toString: String = s"BloopInstall($workspace)"

  def runUnconditionally(buildTool: BuildTool): Future[BloopInstallResult] = {
    val args = buildTool.args(workspace, userConfig, config)
    scribe.info(s"running '${args.mkString(" ")}'")
    val result = runArgumentsUnconditionally(buildTool, args)
    result.foreach { e =>
      if (e.isFailed) {
        // Record the exact command that failed to help troubleshooting.
        scribe.error(s"$buildTool command failed: ${args.mkString(" ")}")
      }
    }
    result
  }

  private def runArgumentsUnconditionally(
      buildTool: BuildTool,
      args: List[String]
  ): Future[BloopInstallResult] = {
    persistChecksumStatus(Status.Started, buildTool)
    val elapsed = new Timer(time)
    val pb = new ProcessBuilder(args: _*)
    pb.directory(workspace.toNIO.toFile)
    pb.redirectErrorStream(buildTool.redirectErrorOutput)
    userConfig().javaHome.foreach(pb.environment().put("JAVA_HOME", _))
    pb.environment().put("COURSIER_PROGRESS", "disable")
    pb.environment().put("METALS_ENABLED", "true")
    pb.environment().put("SCALAMETA_VERSION", BuildInfo.semanticdbVersion)
    // NOTE(olafur): older versions of VS Code don't respect cancellation of
    // window/showMessageRequest, meaning the "cancel build import" button
    // stays forever in view even after successful build import. In newer
    // VS Code versions the message is hidden after a delay.
    val taskResponse =
      languageClient.metalsSlowTask(
        Messages.bloopInstallProgress(buildTool.executableName)
      )
    val completeProcess = Promise[BloopInstallResult]()
    val runningProcess =
      BloopInstall.runSubprocess(pb, completeProcess, taskResponse)

    val processFuture = completeProcess.future.map { result =>
      taskResponse.cancel(false)
      scribe.info(
        s"time: ran '${buildTool.executableName} bloopInstall' in $elapsed"
      )
      result
    }
    statusBar.trackFuture(
      s"Running ${buildTool.executableName} bloopInstall",
      processFuture
    )
    taskResponse.asScala.foreach { item =>
      if (item.cancel) {
        scribe.info("user cancelled build import")
        completeProcess.complete(
          Success(BloopInstallResult.Cancelled)
        )
        BloopInstall.destroyProcess(runningProcess)
      }
    }
    cancelables
      .add(() => BloopInstall.destroyProcess(runningProcess))
      .add(() => taskResponse.cancel(false))

    processFuture.foreach(
      _.toChecksumStatus.foreach(persistChecksumStatus(_, buildTool))
    )
    processFuture
  }

  private val notification = tables.dismissedNotifications.ImportChanges

  private def oldInstallResult(digest: String): Option[BloopInstallResult] = {
    if (notification.isDismissed) {
      Some(BloopInstallResult.Dismissed)
    } else {
      tables.digests.last().collect {
        case Digest(md5, status, _) if md5 == digest =>
          BloopInstallResult.Duplicate(status)
      }
    }
  }

  def runIfApproved(
      buildTool: BuildTool,
      digest: String
  ): Future[BloopInstallResult] = {
    oldInstallResult(digest) match {
      case Some(result) =>
        scribe.info(s"skipping build import with status '${result.name}'")
        Future.successful(result)
      case None =>
        for {
          userResponse <- requestImport(
            buildTools,
            buildTool,
            languageClient,
            digest
          )
          installResult <- {
            if (userResponse.isYes) {
              runUnconditionally(buildTool)
            } else {
              // Don't spam the user with requests during rapid build changes.
              notification.dismiss(2, TimeUnit.MINUTES)
              Future.successful(BloopInstallResult.Rejected)
            }
          }
        } yield installResult
    }
  }

  private def persistChecksumStatus(
      status: Status,
      buildTool: BuildTool
  ): Unit = {
    buildTool.digest(workspace).foreach { checksum =>
      tables.digests.setStatus(checksum, status)
    }
  }

  private def requestImport(
      buildTools: BuildTools,
      buildTool: BuildTool,
      languageClient: MetalsLanguageClient,
      digest: String
  )(implicit ec: ExecutionContext): Future[Confirmation] = {
    tables.digests.setStatus(digest, Status.Requested)
    if (buildTools.isBloop) {
      languageClient
        .showMessageRequest(ImportBuildChanges.params(buildTool.toString))
        .asScala
        .map { item =>
          if (item == dontShowAgain) {
            notification.dismissForever()
          }
          Confirmation.fromBoolean(item == ImportBuildChanges.yes)
        }
    } else {
      languageClient
        .showMessageRequest(ImportBuild.params(buildTool.toString()))
        .asScala
        .map { item =>
          if (item == dontShowAgain) {
            notification.dismissForever()
          }
          Confirmation.fromBoolean(item == ImportBuild.yes)
        }
    }
  }

}

object BloopInstall {

  /**
   * First tries to destroy the process gracefully, with fallback to forcefully.
   */
  private def destroyProcess(process: Process): Unit = {
    process.destroy()
    val destroyed = process.waitFor(2, TimeUnit.SECONDS)
    if (!destroyed) {
      // timeout exceeded, kill process forcefully.
      process.destroyForcibly()
      process.waitFor(2, TimeUnit.SECONDS)
    }
  }

  def runSubprocess(
      pb: ProcessBuilder,
      completeProcess: Promise[BloopInstallResult],
      taskResponse: CompletableFuture[MetalsSlowTaskResult]
  )(implicit ec: ExecutionContext): Process = {
    val runningProcess = pb.start()

    def processLines(b: BufferedReader, process: String => Unit): Future[Unit] =
      Future {
        var line: String = b.readLine()
        while (line != null) {
          process(line)
          line = b.readLine()
        }
      }

    val stdoutReader =
      new BufferedReader(new InputStreamReader(runningProcess.getInputStream));
    val stderrReader =
      new BufferedReader(new InputStreamReader(runningProcess.getErrorStream));

    // start processing output
    val stdoutLoggerProcess = processLines(stdoutReader, scribe.info(_))
    val stdErrLoggerProcess =
      if (!pb.redirectErrorStream)
        processLines(stderrReader, scribe.error(_))
      else
        Future.successful(())

    // report status when streams end
    stdoutLoggerProcess.zip(stdErrLoggerProcess) onComplete { _ =>
      try {
        stdoutReader.close()
        stderrReader.close()
      } catch {
        case _: IOException =>
      }
      val statusCode = runningProcess.waitFor()
      if (!completeProcess.isCompleted) {
        if (statusCode == 0) {
          completeProcess.trySuccess(BloopInstallResult.Installed)
        } else {
          completeProcess.trySuccess(BloopInstallResult.Failed(statusCode))
        }
      }
      scribe.info(s"build tool exit: $statusCode")
      taskResponse.cancel(false)
    }

    runningProcess
  }
}
