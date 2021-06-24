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

package org.alephium.flow.mempool

import io.prometheus.client.Gauge

import org.alephium.flow.core.FlowUtils.AssetOutputInfo
import org.alephium.flow.setting.MemPoolSetting
import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{LockupScript, WorldState}
import org.alephium.util.{AVector, TimeStamp}

/*
 * MemPool is the class to store all the unconfirmed transactions
 *
 * Transactions should be ordered according to weights. The weight is calculated based on fees
 */
class MemPool private (
    group: GroupIndex,
    sharedPools: AVector[SharedPool],
    val txIndexes: TxIndexes,
    val pendingPool: PendingPool
)(implicit
    groupConfig: GroupConfig
) {
  def getSharedPool(index: ChainIndex): SharedPool = {
    assume(group == index.from)
    sharedPools(index.to.value)
  }

  def size: Int = sharedPools.sumBy(_.size) + pendingPool.size

  def contains(index: ChainIndex, transaction: TransactionTemplate): Boolean = {
    contains(index, transaction.id)
  }

  def contains(index: ChainIndex, txId: Hash): Boolean =
    getSharedPool(index).contains(txId) || pendingPool.contains(txId)

  def collectForBlock(index: ChainIndex, maxNum: Int): AVector[TransactionTemplate] =
    getSharedPool(index).collectForBlock(maxNum)

  def getAll(index: ChainIndex): AVector[TransactionTemplate] =
    getSharedPool(index).getAll() ++ pendingPool.getAll(index)

  def isSpent(outputRef: AssetOutputRef): Boolean = {
    pendingPool.indexes.isSpent(outputRef) || txIndexes.isSpent(outputRef)
  }

  def isUnspentInPool(outputRef: AssetOutputRef): Boolean = {
    (txIndexes.outputIndex.contains(outputRef) ||
      pendingPool.indexes.outputIndex.contains(outputRef)) &&
    (!isSpent(outputRef))
  }

  def isDoubleSpending(index: ChainIndex, tx: TransactionTemplate): Boolean = {
    assume(index.from == group)
    tx.unsigned.inputs.exists(input => isSpent(input.outputRef))
  }

  def addNewTx(index: ChainIndex, tx: TransactionTemplate): MemPool.NewTxCategory = {
    if (tx.unsigned.inputs.exists(input => isUnspentInPool(input.outputRef))) {
      if (pendingPool.add(tx, TimeStamp.now())) {
        MemPool.AddedToPendingPool
      } else {
        MemPool.PendingPoolIsFull
      }
    } else {
      if (getSharedPool(index).add(tx, TimeStamp.now())) {
        MemPool.AddedToSharedPool
      } else {
        MemPool.SharedPoolIsFull
      }
    }
  }

  def addToTxPool(
      index: ChainIndex,
      transactions: AVector[TransactionTemplate],
      timeStamp: TimeStamp
  ): Int = {
    getSharedPool(index).add(transactions, timeStamp)
  }

  def removeFromTxPool(index: ChainIndex, transactions: AVector[TransactionTemplate]): Int = {
    val sharedPool = getSharedPool(index)
    sharedPool.remove(transactions)
  }

  def reorg(
      toRemove: AVector[AVector[Transaction]],
      toAdd: AVector[AVector[Transaction]]
  ): (Int, Int) = {
    assume(toRemove.length == groupConfig.groups && toAdd.length == groupConfig.groups)
    val now = TimeStamp.now()

    // First, add transactions from short chains, then remove transactions from canonical chains
    val added = toAdd.foldWithIndex(0)((sum, txs, toGroup) =>
      sum + sharedPools(toGroup).add(txs.map(_.toTemplate), now)
    )
    val removed = toRemove.foldWithIndex(0)((sum, txs, toGroup) =>
      sum + sharedPools(toGroup).remove(txs.map(_.toTemplate))
    )

    (removed, added)
  }

  def getRelevantUtxos(
      lockupScript: LockupScript,
      utxosInBlock: AVector[AssetOutputInfo]
  ): AVector[AssetOutputInfo] = {
    val newUtxos =
      txIndexes.getRelevantUtxos(lockupScript) ++ pendingPool.getRelevantUtxos(lockupScript)

    (utxosInBlock ++ newUtxos).filterNot(asset =>
      txIndexes.isSpent(asset) || pendingPool.indexes.isSpent(asset)
    )
  }

  def updatePendingPool(
      worldState: WorldState.Persisted
  ): IOResult[AVector[TransactionTemplate]] = {
    val now = TimeStamp.now()
    pendingPool.extractReadyTxs(worldState).map { txs =>
      txs.groupBy(_.chainIndex).foreach { case (chainIndex, txss) =>
        addToTxPool(chainIndex, txss, now)
      }
      pendingPool.remove(txs)
      pendingPool.measureTransactionsTotal()
      txs
    }
  }

  def getUtxo(outputRef: TxOutputRef): Option[TxOutput] = outputRef match {
    case ref: AssetOutputRef => getUtxo(ref)
    case _                   => None
  }

  def getUtxo(outputRef: AssetOutputRef): Option[TxOutput] = {
    val result = pendingPool.getUtxo(outputRef).flatMap {
      case Some(output) => Right(Some(output))
      case None         => txIndexes.getUtxo(outputRef)
    }
    result match {
      case Left(_)      => None // the output is spent already
      case Right(value) => value
    }
  }

  def clear(): Unit = {
    sharedPools.foreach(_.clear())
  }
}

object MemPool {
  def empty(
      groupIndex: GroupIndex
  )(implicit groupConfig: GroupConfig, memPoolSetting: MemPoolSetting): MemPool = {
    val sharedTxIndex = TxIndexes.emptySharedPool
    val sharedPools =
      AVector.tabulate(groupConfig.groups) { toGroup =>
        val chainIndex = ChainIndex.unsafe(groupIndex.value, toGroup)
        SharedPool.empty(chainIndex, memPoolSetting.sharedPoolCapacity, sharedTxIndex)
      }
    val pendingPool = PendingPool.empty(groupIndex, memPoolSetting.pendingPoolCapacity)
    new MemPool(groupIndex, sharedPools, sharedTxIndex, pendingPool)
  }

  sealed trait NewTxCategory
  case object AddedToSharedPool  extends NewTxCategory
  case object SharedPoolIsFull   extends NewTxCategory
  case object AddedToPendingPool extends NewTxCategory
  case object PendingPoolIsFull  extends NewTxCategory

  val sharedPoolTransactionsTotal: Gauge = Gauge
    .build(
      "alephium_mempool_shared_pool_transactions_total",
      "Number of transactions in shared pool"
    )
    .labelNames("group_index", "chain_index")
    .register()

  val pendingPoolTransactionsTotal: Gauge = Gauge
    .build(
      "alephium_mempool_pending_pool_transactions_total",
      "Number of transactions in pending pool"
    )
    .labelNames("group_index")
    .register()

}
