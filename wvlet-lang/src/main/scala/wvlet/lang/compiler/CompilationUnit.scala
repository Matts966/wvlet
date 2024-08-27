/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.lang.compiler

import wvlet.lang.compiler
import wvlet.lang.compiler.SourceFile.NoSourceFile
import wvlet.lang.model.NodeLocation
import wvlet.lang.model.plan.{LogicalPlan, NamedRelation, Relation}
import wvlet.log.LogSupport
import wvlet.log.io.{IOUtil, Resource}

import java.io.File
import java.net.URLClassLoader
import java.net.{URI, URL}
import java.util.jar.JarFile

/**
  * Represents a unit for compilation (= source file) and records intermediate data (e.g., plan
  * trees) for the source file
  * @param sourceFile
  */
case class CompilationUnit(sourceFile: SourceFile, isPreset: Boolean = false) extends LogSupport:
  // Untyped plan tree
  var unresolvedPlan: LogicalPlan = LogicalPlan.empty
  // Fully-typed plan tree
  var resolvedPlan: LogicalPlan = LogicalPlan.empty

  var knownSymbols: List[Symbol] = List.empty

  var lastError: Option[Throwable] = None

  // Plans generated for subscriptions
  var subscriptionPlans: List[LogicalPlan] = List.empty[LogicalPlan]

  private var finishedPhases: Set[String] = Set.empty

  def isEmpty: Boolean = this eq CompilationUnit.empty

  def isFailed: Boolean = lastError.isDefined

  def isFinished(phase: Phase): Boolean = finishedPhases.contains(phase.name)
  def setFinished(phase: Phase): Unit   = finishedPhases += phase.name

  def enter(symbol: Symbol): Unit = knownSymbols = symbol :: knownSymbols

  def toSourceLocation(nodeLocation: Option[NodeLocation]) = SourceLocation(this, nodeLocation)

  def findRelationRef(name: String): Option[LogicalPlan] =
    var result: Option[Relation] = None
    resolvedPlan.traverse {
      case r: NamedRelation if r.name.leafName == name =>
        result = Some(r)
    }
    result

end CompilationUnit

object CompilationUnit extends LogSupport:
  val empty: CompilationUnit = CompilationUnit(NoSourceFile)

  def fromString(text: String) = CompilationUnit(SourceFile.fromString(text))

  def fromFile(path: String) = CompilationUnit(SourceFile.fromFile(path))

  def fromPath(path: String): List[CompilationUnit] =
    // Ignore the spec folder by default
    val ignoreRootSpecFolder = !path.startsWith("spec/")
    // List all *.wv files under the path
    val files = listFiles(path, 0, ignoreRootSpecFolder)
    val units =
      files
        .map { file =>
          CompilationUnit(SourceFile.fromFile(file), isPreset = false)
        }
        .toList
    units

  private def listWvletFile(path: String): List[URL] =
    val urls = List.newBuilder[URL]
    import scala.jdk.CollectionConverters.*
    Option(this.getClass.getResource(path)).foreach: r =>
      r.getProtocol match
        case "file" =>
          val files = listFiles(r.getPath, 0, ignoreSpecFolder = true)
          urls ++= files.map(File(_).toURI.toURL)
        case "jar" =>
          val jarPath     = r.getPath.split("!")(0).replaceAll("%20", " ").replaceAll("%25", "%")
          val jarFilePath = jarPath.replace("file:", "")
          val jf          = new JarFile(jarFilePath)
          val wvFilePaths = jf.entries().asScala.filter(_.getName.endsWith(".wv"))
          urls ++=
            wvFilePaths
              .map { j =>
                val url = s"jar:${jarPath}!/${j.getName}"
                URI(url).toURL
              }
              .toList
        case _ =>

    urls.result()

  def fromResourcePath(path: String, isPreset: Boolean): List[CompilationUnit] =
    val urls = listWvletFile(path)
    urls.map { url =>
      CompilationUnit(SourceFile.fromResource(url), isPreset = isPreset)
    }

  private def listFiles(path: String, level: Int, ignoreSpecFolder: Boolean): Seq[String] =
    val f = new java.io.File(path)
    if f.isDirectory then
      if ignoreSpecFolder && level == 1 && f.getName == "spec" then
        Seq.empty
      else
        f.listFiles()
          .flatMap { file =>
            listFiles(file.getPath, level + 1, ignoreSpecFolder)
          }
    else if f.isFile && f.getName.endsWith(".wv") then
      Seq(f.getPath)
    else
      Seq.empty

end CompilationUnit