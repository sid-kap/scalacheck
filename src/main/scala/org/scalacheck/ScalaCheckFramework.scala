/*-------------------------------------------------------------------------*\
**  ScalaCheck                                                             **
**  Copyright (c) 2007-2015 Rickard Nilsson. All rights reserved.          **
**  http://www.scalacheck.org                                              **
**                                                                         **
**  This software is released under the terms of the Revised BSD License.  **
**  There is NO WARRANTY. See the file LICENSE for the full text.          **
\*------------------------------------------------------------------------ */

package org.scalacheck

import sbt.testing._
import scala.language.reflectiveCalls
import java.util.concurrent.atomic.AtomicInteger

private abstract class ScalaCheckRunner(
  val args: Array[String],
  val remoteArgs: Array[String],
  val loader: ClassLoader
) extends Runner {

  val successCount = new AtomicInteger(0)
  val failureCount = new AtomicInteger(0)
  val errorCount = new AtomicInteger(0)
  val testCount = new AtomicInteger(0)

  val params = Test.cmdLineParser.parseParams(args) match {
    case Some(p) => p.withTestCallback(new Test.TestCallback {})
    case None => throw new Exception(s"Invalid ScalaCheck args: ${args.toList}")
  }

  def deserializeTask(task: String, deserializer: String => TaskDef) = {
    val taskDef = deserializer(task)
    val countTestSelectors = taskDef.selectors.toSeq.count {
      case _:TestSelector => true
      case _ => false
    }
    if (countTestSelectors == 0) rootTask(taskDef)
    else checkPropTask(taskDef)
  }

  def serializeTask(task: Task, serializer: TaskDef => String) =
    serializer(task.taskDef)

  def tasks(taskDefs: Array[TaskDef]): Array[Task] = taskDefs.map(rootTask)

  abstract class BaseTask(override val taskDef: TaskDef) extends Task {
    val tags: Array[String] = Array()

    val props: Seq[(String,Prop)] = {
      val fp = taskDef.fingerprint.asInstanceOf[SubclassFingerprint]
      val obj = if (fp.isModule) Platform.loadModule(taskDef.fullyQualifiedName,loader)
                else Platform.newInstance(taskDef.fullyQualifiedName, loader)(Seq())
      obj match {
        case props: Properties => props.properties
        case prop: Prop => Seq("" -> prop)
      }
    }

    def execute(handler: EventHandler, loggers: Array[Logger],
      continuation: Array[Task] => Unit
    ): Unit  = continuation(execute(handler,loggers))
  }

  def rootTask(td: TaskDef) = new BaseTask(td) {
    def execute(handler: EventHandler, loggers: Array[Logger]): Array[Task] =
      props.map(_._1).toSet.toArray map { name =>
        checkPropTask(new TaskDef(td.fullyQualifiedName, td.fingerprint,
          td.explicitlySpecified, Array(new TestSelector(name)))
        )
      }
  }

  def checkPropTask(taskDef: TaskDef) = new BaseTask(taskDef) {
    val names = taskDef.selectors flatMap {
      case ts: TestSelector => Array(ts.testName)
      case _ => Array.empty[String]
    }

    def execute(handler: EventHandler, loggers: Array[Logger]): Array[Task] =
      names flatMap { name =>
        import util.Pretty.{pretty, Params}

        for ((`name`, prop) <- props) {
          val result = Test.check(params.withCustomClassLoader(Some(loader)), prop)

          val event = new Event {
            val status = result.status match {
              case Test.Passed => Status.Success
              case _:Test.Proved => Status.Success
              case _:Test.Failed => Status.Failure
              case Test.Exhausted => Status.Failure
              case _:Test.PropException => Status.Error
            }
            val throwable = result.status match {
              case Test.PropException(_, e, _) => new OptionalThrowable(e)
              case _:Test.Failed => new OptionalThrowable(
                new Exception(pretty(result, Params(0)))
              )
              case _ => new OptionalThrowable()
            }
            val fullyQualifiedName = taskDef.fullyQualifiedName
            val selector = new TestSelector(name)
            val fingerprint = taskDef.fingerprint
            val duration = -1L
          }

          handler.handle(event)

          event.status match {
            case Status.Success => successCount.incrementAndGet()
            case Status.Error => errorCount.incrementAndGet()
            case Status.Skipped => errorCount.incrementAndGet()
            case Status.Failure => failureCount.incrementAndGet()
            case _ => failureCount.incrementAndGet()
          }
          testCount.incrementAndGet()

          // TODO Stack traces should be reported through event
          val verbosityOpts = Set("-verbosity", "-v")
          val verbosity =
            args.grouped(2).filter(twos => verbosityOpts(twos.head))
            .toSeq.headOption.map(_.last).map(_.toInt).getOrElse(0)
          val s = if (result.passed) "+" else "!"
          val n = if (name.isEmpty) taskDef.fullyQualifiedName else name
          val logMsg = s"$s $n: ${pretty(result, Params(verbosity))}"
          loggers.foreach(l =>
            if(l.ansiCodesSupported) 
              l.info((if (result.passed) Console.GREEN else Console.RED) + logMsg + Console.RESET)
            else
              l.info(logMsg))
        }

        Array.empty[Task]
      }
  }

}

final class ScalaCheckFramework extends Framework {

  private def mkFP(mod: Boolean, cname: String, noArgCons: Boolean = true) =
    new SubclassFingerprint {
      def superclassName(): String = cname
      val isModule = mod
      def requireNoArgConstructor(): Boolean = noArgCons
    }

  val name = "ScalaCheck"

  def fingerprints: Array[Fingerprint] = Array(
    mkFP(false, "org.scalacheck.Properties"),
    mkFP(false, "org.scalacheck.Prop"),
    mkFP(true, "org.scalacheck.Properties"),
    mkFP(true, "org.scalacheck.Prop")
  )

  def runner(args: Array[String], remoteArgs: Array[String],
    loader: ClassLoader
  ): Runner = new ScalaCheckRunner(args, remoteArgs, loader) {

    def receiveMessage(msg: String): Option[String] = msg(0) match {
      case 'd' =>
        val Array(t,s,f,e) = msg.tail.split(',')
        testCount.addAndGet(t.toInt)
        successCount.addAndGet(s.toInt)
        failureCount.addAndGet(f.toInt)
        errorCount.addAndGet(e.toInt)
        None
    }

    def done = {
      val heading = if (testCount.get == successCount.get) "Passed" else "Failed"
      s"$heading: Total $testCount, Failed $failureCount, Errors $errorCount, Passed $successCount"
    }

  }

  def slaveRunner(args: Array[String], remoteArgs: Array[String],
    loader: ClassLoader, send: String => Unit
  ): Runner = new ScalaCheckRunner(args, remoteArgs, loader) {

    def receiveMessage(msg: String) = None

    def done = {
      send(s"d$testCount,$successCount,$failureCount,$errorCount")
      ""
    }

  }

}
