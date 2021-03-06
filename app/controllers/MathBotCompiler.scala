package controllers

import java.net.URLDecoder

import actors._
import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.Materializer
import akka.util.Timeout
import compiler.processor.{AnimationType, Frame}
import compiler.{Cell, Point}
import javax.inject.Inject
import actors.messages.{ClientRobotState, PreparedStepData}
import loggers.MathBotLogger
import model.PlayerTokenModel
import model.models._
import play.api.{Configuration, Environment}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.libs.streams.ActorFlow
import play.api.mvc._
import play.modules.reactivemongo.ReactiveMongoApi
import utils.CompilerConfiguration

import scala.concurrent.Future
import scala.concurrent.duration._

object MathBotCompiler {

  case class ClientCell(location: Point, items: List[String])

  object ClientCell {
    def apply(loc: Point, cell: Cell): ClientCell = {
      val contents = cell.contents.map(element => element.image)
      ClientCell(loc, contents)
    }
  }

  case class ClientGrid(cells: Set[ClientCell])

  object ClientGrid {
    def apply(grid: compiler.Grid): ClientGrid = {
      val cells =
        grid.grid.map(g => ClientCell(Point(g._1._1, g._1._2), g._2)).toSet
      ClientGrid(cells)
    }
  }

  case class ClientFrame(robotState: ClientRobotState,
                         programState: String,
                         stats: Option[Stats],
                         stepData: Option[PreparedStepData]) {
    def isSuccess() = programState == "success"
    def isFailure() = programState == "failure"
  }

  object ClientFrame {
    def apply(frame: Frame, stats: Option[Stats] = None, stepData: Option[PreparedStepData] = None): ClientFrame =
      ClientFrame(frame, "running", stats, stepData)

    // stepData is the step data to render at this point
    def success(frame: Frame, stats: Stats, stepData: PreparedStepData): ClientFrame =
      ClientFrame(frame, "success", Some(stats), Some(stepData))

    // stepData is the step data to render at this point
    def failure(frame: Frame, stats: Stats, stepData: PreparedStepData): ClientFrame =
      ClientFrame(frame, "failure", Some(stats), Some(stepData))

    def apply(frame: Frame,
              programState: String,
              stats: Option[Stats],
              stepData: Option[PreparedStepData]): ClientFrame =
      ClientFrame(ClientRobotState(frame), programState, stats, stepData)
  }

  case class ClientResponse(frames: List[ClientFrame] = List.empty[ClientFrame],
                            problem: Option[Problem] = None,
                            halted: Option[Boolean] = None,
                            error: Option[String] = None)

}

class MathBotCompiler @Inject()(val reactiveMongoApi: ReactiveMongoApi)(implicit system: ActorSystem,
                                                                        mat: Materializer,
                                                                        mathBotLogger: MathBotLogger,
                                                                        environment: Environment,
                                                                        configuration: Configuration)
    extends Controller
    with PlayerTokenModel
    with utils.SameOriginCheck {

  val levelActor =
    system.actorOf(LevelGenerationActor.props(reactiveMongoApi, mathBotLogger, environment), "level-compiler-actor")
  val statsActor = system.actorOf(StatsActor.props(system, reactiveMongoApi, mathBotLogger), "stats-compiler-actor")

  val compilerConfiguration = CompilerConfiguration(
    maxProgramSteps = configuration.getInt("mathbot.maxProgramSteps").getOrElse(10000)
  )

  def wsPath(tokenId: String): Action[AnyContent] = Action { implicit request: RequestHeader =>
    val url = routes.MathBotCompiler.compileWs(tokenId).webSocketURL()
    val changeSsl =
      if (url.contains("localhost")) url else url.replaceFirst("ws", "wss")
    Ok(changeSsl)
  }

  def compileWs(encodedTokenId: String): WebSocket =
    WebSocket.accept[JsValue, JsValue] {
      case rh if sameOriginCheck(rh) =>
        SocketRequestConvertFlow()
          .via(
            ActorFlow.actorRef(
              out =>
                CompilerActor.props(out,
                                    URLDecoder.decode(encodedTokenId, "UTF-8"),
                                    reactiveMongoApi,
                                    statsActor,
                                    levelActor,
                                    mathBotLogger,
                                    compilerConfiguration)
            )
          )
          .via(
            SocketResponseConvertFlow()
          )
      case rejected =>
        ActorFlow.actorRef(out => {
          SameOriginFailedActor.props(out, mathBotLogger)
        })
    }

  def compile(encodedTokenId: String) = Action.async { implicit request =>
    class FakeActor extends Actor {

      override def receive: Receive = {
        case _ =>
      }
    }

    val fakeActorProps = Props(new FakeActor)
    val fakeActor = system.actorOf(fakeActorProps)

    implicit val timeout: Timeout = 500.seconds

    request.body.asJson match {
      case Some(json) =>
        val sr = SocketRequestConvertFlow.jsonToCompilerCommand(json)

        val compilerProps =
          CompilerActor.props(fakeActor,
                              URLDecoder.decode(encodedTokenId, "UTF-8"),
                              reactiveMongoApi,
                              statsActor,
                              levelActor,
                              mathBotLogger,
            compilerConfiguration)
        val compiler = system.actorOf(compilerProps)

        (compiler ? sr)
          .map(SocketResponseConvertFlow.compilerResponseToJson)
          .map(Ok(_))
      case _ =>
        Future(NoContent)
    }

  }
}
