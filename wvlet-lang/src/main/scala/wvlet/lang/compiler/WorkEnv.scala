package wvlet.lang.compiler

import wvlet.log.LogFormatter.SourceCodeLogFormatter
import wvlet.log.LogLevel.ALL
import wvlet.log.{LogLevel, LogRotationHandler, Logger}

/**
  * Working directory for finding .wv files and target folders for logs and cache
  * @param path
  * @param logLevel
  */
case class WorkEnv(path: String = ".", logLevel: LogLevel = Logger.getDefaultLogLevel)
    extends WorkEnvCompat:
  lazy val hasWvletFiles: Boolean = Option(new java.io.File(path).listFiles())
    .exists(_.exists(_.getName.endsWith(".wv")))

  def targetFolder: String =
    if hasWvletFiles then
      s"${path}/target"
    else
      s"${sys.props("user.home")}/.cache/wvlet/target"

  def logFile: String   = s"${targetFolder}/wvlet-out.log"
  def errorFile: String = s"${targetFolder}/wvlet-err.log"

  def cacheFolder: String =
    if hasWvletFiles then
      // Use the target folder for the folder containing .wv files
      s"${targetFolder}/.cache/wvlet"
    else
      // Use the global folder at the user home for an arbitrary directory
      s"${sys.props("user.home")}/.cache/wvlet"

  lazy val errorLogger: Logger =
    val l = Logger("wvlet.lang.runner.error")
    initLogger(l)
    l.setLogLevel(logLevel)
    l

  lazy val outLogger: Logger =
    val l = Logger("wvlet.lang.runner.out")
    initLogger(l)
    l.setLogLevel(logLevel)
    l

end WorkEnv
