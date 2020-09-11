package org.alephium.wallet.storage

import java.io.{File, PrintWriter}
import java.nio.file.{Files, Path}
import java.util.UUID

import scala.io.Source
import scala.util.Using

import akka.util.ByteString
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import io.circe.parser.decode
import io.circe.syntax._

import org.alephium.crypto.AES
import org.alephium.crypto.wallet.BIP32
import org.alephium.crypto.wallet.BIP32.ExtendedPrivateKey
import org.alephium.wallet.Constants
import org.alephium.wallet.circe.UtilCodecs

trait SecretStorage {
  def lock(): Unit
  def unlock(password: String): Either[String, Unit]
  def getPrivateKey(): Option[ExtendedPrivateKey]
  def deriveNextKey(): Either[SecretStorage.SecretStorageError, ExtendedPrivateKey]
}

object SecretStorage extends UtilCodecs {

  sealed trait SecretStorageError

  case object Locked          extends SecretStorageError
  case object CannotDeriveKey extends SecretStorageError

  def fromFile(file: File, password: String): Either[String, SecretStorage] = {
    seedFromFile(file, password).map(_ => new Impl(file))
  }

  def apply(seed: ByteString, password: String, secretDir: Path): Either[String, SecretStorage] = {

    val encryption = AES.encrypt(seed, password)

    val uuid = UUID.nameUUIDFromBytes(encryption.encrypted.toArray)

    Using
      .Manager { use =>
        Files.createDirectories(secretDir)
        val file      = new File(s"$secretDir/$uuid.json")
        val outWriter = use(new PrintWriter(file))

        // scalastyle:off regex
        outWriter.write(encryption.asJson.noSpaces)
        // scalastyle:on
        new Impl(file)
      }
      .toEither
      .left
      .map(_.getMessage)
  }

  private final case class State(seed: ByteString, privateKey: ExtendedPrivateKey)

  private class Impl(file: File) extends SecretStorage {

    private var state: Option[State] = None

    override def lock(): Unit = {
      state = None
    }

    override def unlock(password: String): Either[String, Unit] = {
      for {
        seed <- seedFromFile(file, password)
        privateKey <- BIP32
          .btcMasterKey(seed)
          .derive(Constants.path.toSeq)
          .toRight(s"Cannot derive key from path ${Constants.pathStr}")
      } yield {
        state = Some(State(seed, privateKey))
      }
    }

    override def getPrivateKey(): Option[ExtendedPrivateKey] = state.map(_.privateKey)

    @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
    override def deriveNextKey(): Either[SecretStorageError, ExtendedPrivateKey] = {
      state.toRight(Locked).flatMap {
        case State(seed, privateKey) =>
          val currentIndex = privateKey.path.last

          val maybeNewPrivateKey = for {
            parent <- BIP32.btcMasterKey(seed).derive(Constants.path.toSeq.init)
            child  <- parent.derive(currentIndex + 1)
          } yield child

          maybeNewPrivateKey
            .toRight(CannotDeriveKey)
            .map { newPrivateKey =>
              state = Some(State(seed, newPrivateKey))
              newPrivateKey
            }
      }
    }
  }

  private def seedFromFile(file: File, password: String): Either[String, ByteString] = {
    Using(Source.fromFile(file)) { source =>
      val rawFile = source.getLines().mkString
      for {
        encrypted <- decode[AES.Encrypted](rawFile).left.map(_.getMessage)
        seed      <- AES.decrypt(encrypted, password).toEither.left.map(_.getMessage)
      } yield {
        source.close()
        seed
      }
    }.toEither.left.map(_.getMessage).flatten
  }

  implicit val codec: Codec[AES.Encrypted] = deriveCodec[AES.Encrypted]
}
