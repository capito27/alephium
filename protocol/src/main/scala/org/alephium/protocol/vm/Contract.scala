package org.alephium.protocol.vm

import org.alephium.protocol.ALF
import org.alephium.serde._
import org.alephium.util.AVector

trait ContractAddress

final case class Method[Ctx <: Context](
    localsType: AVector[Val.Type],
    returnType: AVector[Val.Type],
    instrs: AVector[Instr[Ctx]]
) {
  def check(args: AVector[Val]): ExeResult[Unit] = {
    if (args.length != localsType.length)
      Left(InvalidMethodArgLength(args.length, localsType.length))
    else if (!args.forallWithIndex((v, index) => v.tpe == localsType(index))) {
      Left(InvalidMethodParamsType)
    } else Right(())
  }
}

object Method {
  implicit val statelessSerde: Serde[Method[StatelessContext]] =
    Serde.forProduct3(Method[StatelessContext], t => (t.localsType, t.returnType, t.instrs))
  implicit val statefulSerde: Serde[Method[StatefulContext]] =
    Serde.forProduct3(Method[StatefulContext], t => (t.localsType, t.returnType, t.instrs))
}

sealed trait Contract[Ctx <: Context] {
  def fields: AVector[Val.Type]
  def methods: AVector[Method[Ctx]]
}

sealed abstract class Script[Ctx <: Context] extends Contract[Ctx] {
  def toObject(fields: AVector[Val]): ScriptObj[Ctx]

  def startFrame(ctx: Ctx,
                 initVals: AVector[Val],
                 args: AVector[Val],
                 returnTo: AVector[Val] => ExeResult[Unit]): Frame[Ctx] = {
    val obj = this.toObject(initVals)
    Frame.build(ctx, obj, args: AVector[Val], returnTo)
  }
}

final case class StatelessScript(
    fields: AVector[Val.Type],
    methods: AVector[Method[StatelessContext]]
) extends Script[StatelessContext] {
  override def toObject(fields: AVector[Val]): ScriptObj[StatelessContext] = {
    StatelessScriptObject(this, fields.toArray)
  }
}

object StatelessScript {
  implicit val serde: Serde[StatelessScript] =
    Serde.forProduct2(StatelessScript.apply, t => (t.fields, t.methods))
}

final case class StatefulScript(
    fields: AVector[Val.Type],
    methods: AVector[Method[StatefulContext]]
) extends Script[StatefulContext] {
  override def toObject(fields: AVector[Val]): ScriptObj[StatefulContext] = {
    StatefulScriptObject(this, fields.toArray)
  }
}

object StatefulScript {
  implicit val serde: Serde[StatefulScript] =
    Serde.forProduct2(StatefulScript.apply, t => (t.fields, t.methods))
}

final case class StatefulContract(
    fields: AVector[Val.Type],
    methods: AVector[Method[StatefulContext]]
) extends Contract[StatefulContext]

object StatefulContract {
  implicit val serde: Serde[StatefulContract] =
    Serde.forProduct2(StatefulContract.apply, t => (t.fields, t.methods))
}

trait ContractObj[Ctx <: Context] {
  def code: Contract[Ctx]
  def fields: Array[Val]
}

trait ScriptObj[Ctx <: Context] extends ContractObj[Ctx]

final case class StatelessScriptObject(code: StatelessScript, fields: Array[Val])
    extends ScriptObj[StatelessContext]

final case class StatefulScriptObject(code: StatefulScript, fields: Array[Val])
    extends ScriptObj[StatefulContext]

final case class StatefulContractObject(code: StatefulContract,
                                        fields: Array[Val],
                                        address: ContractAddress,
                                        codeHash: ALF.Hash)
    extends ContractObj[StatefulContext]
