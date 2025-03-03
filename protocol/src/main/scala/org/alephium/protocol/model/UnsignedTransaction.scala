// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.model

import org.alephium.macros.HashSerde
import org.alephium.protocol.ALPH
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.vm._
import org.alephium.serde._
import org.alephium.util.{AVector, TimeStamp, U256}

/** Up to one new token might be issued in each transaction exception for the coinbase transaction
  * The id of the new token will be hash of the first input
  *
  * @param version the version of the tx
  * @param networkId the id of the chain which can accept the tx
  * @param scriptOpt optional script for invoking stateful contracts
  * @param gasAmount the amount of gas can be used for tx execution
  * @param inputs a vector of TxInput
  * @param fixedOutputs a vector of TxOutput. ContractOutput are put in front of AssetOutput
  */
@HashSerde
final case class UnsignedTransaction(
    version: Byte,
    networkId: NetworkId,
    scriptOpt: Option[StatefulScript],
    gasAmount: GasBox,
    gasPrice: GasPrice,
    inputs: AVector[TxInput],
    fixedOutputs: AVector[AssetOutput]
) extends AnyRef {
  // this might only works for validated tx
  def fromGroup(implicit config: GroupConfig): GroupIndex = {
    inputs.head.fromGroup
  }

  // this might only works for validated tx
  def toGroup(implicit config: GroupConfig): GroupIndex = {
    val from    = fromGroup
    val outputs = fixedOutputs
    if (outputs.isEmpty) {
      from
    } else {
      val index = outputs.indexWhere(_.toGroup != from)
      if (index == -1) {
        from
      } else {
        outputs(index).toGroup
      }
    }
  }

  // this might only works for validated tx
  def chainIndex(implicit config: GroupConfig): ChainIndex = ChainIndex(fromGroup, toGroup)
}

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] = Serde.forProduct7(
    UnsignedTransaction.apply,
    t => (t.version, t.networkId, t.scriptOpt, t.gasAmount, t.gasPrice, t.inputs, t.fixedOutputs)
  )

  def apply(
      scriptOpt: Option[StatefulScript],
      startGas: GasBox,
      gasPrice: GasPrice,
      inputs: AVector[TxInput],
      fixedOutputs: AVector[AssetOutput]
  )(implicit networkConfig: NetworkConfig): UnsignedTransaction = {
    new UnsignedTransaction(
      DefaultTxVersion,
      networkConfig.networkId,
      scriptOpt,
      startGas,
      gasPrice,
      inputs,
      fixedOutputs
    )
  }

  def apply(
      txScriptOpt: Option[StatefulScript],
      inputs: AVector[TxInput],
      fixedOutputs: AVector[AssetOutput]
  )(implicit networkConfig: NetworkConfig): UnsignedTransaction = {
    UnsignedTransaction(
      DefaultTxVersion,
      networkConfig.networkId,
      txScriptOpt,
      minimalGas,
      defaultGasPrice,
      inputs,
      fixedOutputs
    )
  }

  def apply(inputs: AVector[TxInput], fixedOutputs: AVector[AssetOutput])(implicit
      networkConfig: NetworkConfig
  ): UnsignedTransaction = {
    UnsignedTransaction(
      DefaultTxVersion,
      networkConfig.networkId,
      None,
      minimalGas,
      defaultGasPrice,
      inputs,
      fixedOutputs
    )
  }

  def coinbase(inputs: AVector[TxInput], fixedOutputs: AVector[AssetOutput])(implicit
      networkConfig: NetworkConfig
  ): UnsignedTransaction = {
    UnsignedTransaction(
      DefaultTxVersion,
      networkConfig.networkId,
      None,
      minimalGas,
      minimalGasPrice,
      inputs,
      fixedOutputs
    )
  }

  def build(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      inputs: AVector[(AssetOutputRef, AssetOutput)],
      outputs: AVector[TxOutputInfo],
      gas: GasBox,
      gasPrice: GasPrice
  )(implicit networkConfig: NetworkConfig): Either[String, UnsignedTransaction] = {
    assume(gas >= minimalGas)
    assume(gasPrice.value <= ALPH.MaxALPHValue)
    val gasFee = gasPrice * gas
    for {
      _               <- checkWithMaxTxInputNum(inputs)
      _               <- checkUniqueInputs(inputs)
      _               <- checkMinimalAlphPerOutput(outputs)
      _               <- checkMaximumTokenNumPerOutput(outputs)
      _               <- checkTokenValuesNonZero(outputs)
      alphRemainder   <- calculateAlphRemainder(inputs, outputs, gasFee)
      tokensRemainder <- calculateTokensRemainder(inputs, outputs)
      changeOutputOpt <- calculateChangeOutput(alphRemainder, tokensRemainder, fromLockupScript)
    } yield {
      var txOutputs = outputs.map {
        case TxOutputInfo(toLockupScript, amount, tokens, lockTimeOpt) =>
          TxOutput.asset(amount, toLockupScript, tokens, lockTimeOpt)
      }

      changeOutputOpt.foreach { changeOutput =>
        txOutputs = txOutputs :+ changeOutput
      }

      UnsignedTransaction(
        DefaultTxVersion,
        networkConfig.networkId,
        scriptOpt = None,
        gas,
        gasPrice,
        inputs.map { case (ref, _) =>
          TxInput(ref, fromUnlockScript)
        },
        txOutputs
      )
    }
  }

  def checkUniqueInputs(
      assets: AVector[(AssetOutputRef, AssetOutput)]
  ): Either[String, Unit] = {
    check(
      failCondition = assets.length > assets.map(_._1).toSet.size,
      "Inputs not unique"
    )
  }

  def checkWithMaxTxInputNum(
      assets: AVector[(AssetOutputRef, AssetOutput)]
  ): Either[String, Unit] = {
    check(
      failCondition = assets.length > ALPH.MaxTxInputNum,
      "Too many inputs for the transfer, consider to reduce the amount to send, or use the `sweep-all` endpoint to consolidate the inputs first"
    )
  }

  def calculateAlphRemainder(
      inputs: AVector[(AssetOutputRef, AssetOutput)],
      outputs: AVector[TxOutputInfo],
      gasFee: U256
  ): Either[String, U256] = {
    for {
      inputSum     <- inputs.foldE(U256.Zero)(_ add _._2.amount toRight "Input amount overflow")
      outputAmount <- outputs.foldE(U256.Zero)(_ add _.alphAmount toRight "Output amount overflow")
      remainder0   <- inputSum.sub(outputAmount).toRight("Not enough balance")
      remainder    <- remainder0.sub(gasFee).toRight("Not enough balance for gas fee")
    } yield remainder
  }

  def calculateTokensRemainder(
      inputs: AVector[(AssetOutputRef, AssetOutput)],
      outputs: AVector[TxOutputInfo]
  ): Either[String, AVector[(TokenId, U256)]] = {
    for {
      inputs    <- calculateTotalAmountPerToken(inputs.flatMap(_._2.tokens))
      outputs   <- calculateTotalAmountPerToken(outputs.flatMap(_.tokens))
      _         <- checkNoNewTokensInOutputs(inputs, outputs)
      remainder <- calculateRemainingTokens(inputs, outputs)
    } yield {
      remainder.filterNot(_._2 == U256.Zero)
    }
  }

  // TODO: Here if we have too many tokens in the change output, we could split it into
  //       several change outputs so that the built transaction can still be valid
  def calculateChangeOutput(
      alphRemainder: U256,
      tokensRemainder: AVector[(TokenId, U256)],
      fromLockupScript: LockupScript.Asset
  ): Either[String, Option[AssetOutput]] = {
    if (alphRemainder == U256.Zero && tokensRemainder.isEmpty) {
      Right(None)
    } else if (tokensRemainder.length > maxTokenPerUtxo) {
      Left(s"Too many tokens in the change output, maximal number $maxTokenPerUtxo")
    } else {
      if (alphRemainder > minimalAlphAmountPerTxOutput(tokensRemainder.length)) {
        Right(Some(TxOutput.asset(alphRemainder, tokensRemainder, fromLockupScript)))
      } else {
        Left("Not enough ALPH for change output")
      }
    }
  }

  private def checkMinimalAlphPerOutput(
      outputs: AVector[TxOutputInfo]
  ): Either[String, Unit] = {
    check(
      failCondition = outputs.exists { output =>
        output.alphAmount < minimalAlphAmountPerTxOutput(output.tokens.length)
      },
      "Not enough ALPH for transaction output"
    )
  }

  private def checkMaximumTokenNumPerOutput(
      outputs: AVector[TxOutputInfo]
  ): Either[String, Unit] = {
    check(
      failCondition = outputs.exists(_.tokens.length > maxTokenPerUtxo),
      s"Too many tokens in the transaction output, maximal number $maxTokenPerUtxo"
    )
  }

  private def checkTokenValuesNonZero(
      outputs: AVector[TxOutputInfo]
  ): Either[String, Unit] = {
    check(
      failCondition = outputs.exists(_.tokens.exists(_._2.isZero)),
      "Value is Zero for one or many tokens in the transaction output"
    )
  }

  def calculateTotalAmountPerToken(
      tokens: AVector[(TokenId, U256)]
  ): Either[String, AVector[(TokenId, U256)]] = {
    tokens.foldE(AVector.empty[(TokenId, U256)]) { case (acc, (id, amount)) =>
      val index = acc.indexWhere(_._1 == id)
      if (index == -1) {
        Right(acc :+ (id -> amount))
      } else {
        acc(index)._2.add(amount).toRight(s"Amount overflow for token $id").map { amt =>
          acc.replace(index, (id, amt))
        }
      }
    }
  }

  private def checkNoNewTokensInOutputs(
      inputs: AVector[(TokenId, U256)],
      outputs: AVector[(TokenId, U256)]
  ): Either[String, Unit] = {
    val newTokens = outputs.map(_._1).toSet -- inputs.map(_._1).toSet
    check(
      failCondition = newTokens.nonEmpty,
      s"New tokens found in outputs: $newTokens"
    )
  }

  private def calculateRemainingTokens(
      inputTokens: AVector[(TokenId, U256)],
      outputTokens: AVector[(TokenId, U256)]
  ): Either[String, AVector[(TokenId, U256)]] = {
    inputTokens.foldE(AVector.empty[(TokenId, U256)]) { case (acc, (inputId, inputAmount)) =>
      val outputAmount = outputTokens.find(_._1 == inputId).fold(U256.Zero)(_._2)
      inputAmount.sub(outputAmount).toRight(s"Not enough balance for token $inputId").map {
        remainder =>
          acc :+ (inputId -> remainder)
      }
    }
  }

  @inline private def check(failCondition: Boolean, errorMessage: String): Either[String, Unit] = {
    Either.cond(!failCondition, (), errorMessage)
  }

  final case class TxOutputInfo(
      lockupScript: LockupScript.Asset,
      alphAmount: U256,
      tokens: AVector[(TokenId, U256)],
      lockTime: Option[TimeStamp]
  )
}
