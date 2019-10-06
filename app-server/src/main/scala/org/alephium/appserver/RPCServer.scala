package org.alephium.appserver

import java.time.Instant

import scala.concurrent._
import scala.concurrent.duration.Duration

import akka.NotUsed
import akka.actor.ActorRef
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import io.circe._
import io.circe.syntax._

import org.alephium.appserver.RPCModel.{FetchEntry, FetchRequest}
import org.alephium.flow.client.{FairMiner, Miner, Node}
import org.alephium.flow.core.MultiChain
import org.alephium.flow.network.DiscoveryServer
import org.alephium.flow.platform.Mode
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model.{BlockHeader, CliqueInfo}
import org.alephium.rpc.{CORSHandler, JsonRPCHandler}
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC.{Notification, Response}
import org.alephium.rpc.util.AVectorJson._
import org.alephium.util.EventBus

trait RPCServer extends CORSHandler with StrictLogging {
  import RPCServer._

  def mode: Mode

  private val cliquesEncoder = encodeAVector[CliqueInfo]
  def handler(node: Node, miner: ActorRef)(implicit consus: ConsensusConfig,
                                           rpc: RPCConfig,
                                           timeout: Timeout,
                                           EC: ExecutionContext): JsonRPC.Handler = Map.apply(
    "blockflow_fetch" -> { req =>
      Future { blockflowFetch(node, req) }
    },
    "clique_info" -> { req =>
      node.discoveryServer.ask(DiscoveryServer.GetPeerCliques).map { result =>
        val cliques = result.asInstanceOf[DiscoveryServer.PeerCliques]
        val json    = cliquesEncoder.apply(cliques.peers)
        Response.successful(req, json)
      }
    },
    "mining_start" -> { req =>
      Future {
        miner ! Miner.Start
        Response.successful(req)
      }
    },
    "mining_stop" -> { req =>
      Future {
        miner ! Miner.Stop
        Response.successful(req)
      }
    }
  )

  def handleEvent(event: EventBus.Event): TextMessage = {
    // TODO Replace with concrete implementation.
    event match {
      case _ =>
        val ts = System.currentTimeMillis()
        val result = Notification("events_fake", ts.asJson)
        TextMessage(result.asJson.noSpaces)
    }
  }

  def runServer(): Future[Unit] = {
    val node = mode.node

    implicit val system           = node.system
    implicit val materializer     = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    implicit val config           = mode.profile
    implicit val rpcConfig        = RPCConfig.load(config.aleph)
    implicit val askTimeout       = Timeout(Duration.fromNanos(rpcConfig.askTimeout.toNanos))

    val miner = {
      val props = FairMiner.props(node).withDispatcher("akka.actor.mining-dispatcher")
      system.actorOf(props, s"FairMiner")
    }

    val routeHttp =
      corsHandler(JsonRPCHandler.routeHttp(handler(node, miner)))

    def wsFlow(actor: ActorRef, source: Source[Nothing, NotUsed]): Flow[Any, TextMessage, Unit] = {
      Flow
        .fromSinkAndSourceCoupled(Sink.ignore, source.map(handleEvent))
        .watchTermination() { (_, termination) =>
          termination.onComplete(_ => node.eventBus.tell(EventBus.Unsubscribe, actor))
        }
    }

    val routeWs =
      path("events") {
        corsHandler(get {
          extractUpgradeToWebSocket { upgrade =>
            val (actor, source) =
              Source.actorRef(bufferSize, OverflowStrategy.fail).preMaterialize()
            node.eventBus.tell(EventBus.Subscribe, actor)
            val response = upgrade.handleMessages(wsFlow(actor, source))
            complete(response)
          }
        })
      }

    Http().bindAndHandle(routeHttp, rpcConfig.networkInterface, mode.rpcHttpPort).map(_ => ())
    Http().bindAndHandle(routeWs, rpcConfig.networkInterface, mode.rpcWsPort).map(_     => ())
  }
}

object RPCServer extends StrictLogging {
  import JsonRPC._

  val bufferSize = 64

  implicit val encodeCliqueInfo: Encoder[CliqueInfo] = new Encoder[CliqueInfo] {
    final def apply(ci: CliqueInfo): Json = {
      Json.obj(("id", Json.fromString(ci.id.toString)),
               ("peers", encodeAVector[String].apply(ci.peers.map(_.toString))))
    }
  }

  def blockflowFetch(node: Node, req: Request)(implicit rpc: RPCConfig,
                                               cfg: ConsensusConfig): Response = {
    req.paramsAs[FetchRequest] match {
      case Right(query) =>
        val now        = Instant.now()
        val lowerBound = now.minus(rpc.blockflowFetchMaxAge).toEpochMilli

        val from = query.from match {
          case Some(ts) => Math.max(ts, lowerBound)
          case None     => lowerBound
        }

        val headers = node.blockFlow.getHeadersUnsafe(header => header.timestamp > from)
        val blocks  = headers.map(blockHeaderEncoder(node.blockFlow).apply)

        val json = Json.obj(("blocks", Json.arr(blocks: _*)))

        Response.successful(req, json)
      case Left(failure) => failure
    }
  }

  def blockHeaderEncoder(chain: MultiChain)(
      implicit config: ConsensusConfig): Encoder[BlockHeader] = new Encoder[BlockHeader] {
    final def apply(header: BlockHeader): Json = {
      import io.circe.syntax._

      val index = header.chainIndex

      FetchEntry(
        hash      = header.shortHex,
        timestamp = header.timestamp,
        chainFrom = index.from.value,
        chainTo   = index.to.value,
        height    = chain.getHeight(header),
        deps      = header.blockDeps.toIterable.map(_.shortHex).toList
      ).asJson
    }
  }
}
