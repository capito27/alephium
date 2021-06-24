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

import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.util._

/*
 * Transaction pool implementation
 */
class SharedPool private (
    val chainIndex: ChainIndex,
    val pool: ValueSortedMap[Hash, TransactionTemplate],
    val timestamps: ValueSortedMap[Hash, TimeStamp],
    val sharedTxIndex: TxIndexes,
    val capacity: Int
) extends RWLock {

  def isFull: Boolean = pool.size == capacity

  def size: Int = pool.size

  def contains(txId: Hash): Boolean = readOnly {
    pool.contains(txId)
  }

  def collectForBlock(maxNum: Int): AVector[TransactionTemplate] = readOnly {
    pool.getMaxValues(maxNum)
  }

  def getAll(): AVector[TransactionTemplate] = readOnly {
    pool.getAll()
  }

  def add(transactions: AVector[TransactionTemplate], timeStamp: TimeStamp): Int = writeOnly {
    val result = transactions.fold(0) { case (acc, tx) =>
      acc + _add(tx, timeStamp)
    }
    measureTransactionsTotal()
    result
  }

  def add(tx: TransactionTemplate, timeStamp: TimeStamp): Boolean = writeOnly {
    _add(tx, timeStamp) != 0
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def _add(tx: TransactionTemplate, timeStamp: TimeStamp): Int = {
    if (isFull) {
      val lowestWeightTxId = pool.min
      val lowestWeightTx   = pool(lowestWeightTxId)
      if (SharedPool.txOrdering.gt(tx, lowestWeightTx)) {
        _remove(lowestWeightTxId)
        __add(tx, timeStamp)
        1
      } else {
        0
      }
    } else {
      __add(tx, timeStamp)
      1
    }
  }

  def __add(tx: TransactionTemplate, timeStamp: TimeStamp): Unit = {
    pool.put(tx.id, tx)
    timestamps.put(tx.id, timeStamp)
    sharedTxIndex.add(tx)
  }

  def remove(transactions: AVector[TransactionTemplate]): Int = writeOnly {
    val sizeBefore = size
    transactions.foreach(tx => _remove(tx.id))
    measureTransactionsTotal()
    val sizeAfter = size
    sizeBefore - sizeAfter
  }

  def _remove(txId: Hash): Unit = {
    pool.get(txId).foreach { tx =>
      pool.remove(txId)
      timestamps.remove(txId)
      sharedTxIndex.remove(tx)
    }
  }

  def clear(): Unit = writeOnly {
    pool.clear()
    timestamps.clear()
  }

  private val transactionsTotalLabeled = MemPool.sharedPoolTransactionsTotal
    .labels(chainIndex.from.value.toString, chainIndex.to.value.toString)
  def measureTransactionsTotal(): Unit = {
    transactionsTotalLabeled.set(size.toDouble)
  }
}

object SharedPool {
  def empty(chainIndex: ChainIndex, capacity: Int, sharedTxIndex: TxIndexes): SharedPool =
    new SharedPool(
      chainIndex,
      ValueSortedMap.empty,
      ValueSortedMap.empty,
      sharedTxIndex,
      capacity
    )

  implicit val txOrdering: Ordering[TransactionTemplate] =
    Ordering.by[TransactionTemplate, (U256, Hash)](tx => (tx.unsigned.gasPrice.value, tx.id))
}
