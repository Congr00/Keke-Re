package com.codingame.game.engine

import arrow.core.*
import arrow.core.computations.option
import org.hexworks.amethyst.api.*
import org.hexworks.amethyst.api.base.BaseAttribute
import org.hexworks.amethyst.api.base.BaseEntityType
import org.hexworks.amethyst.api.base.BaseFacet
import org.hexworks.amethyst.api.entity.Entity
import org.hexworks.amethyst.api.entity.EntityType
import kotlin.reflect.KClass

class World(private val stride: Int) {
    private lateinit var entities: Array<Option<AnyGameEntity>>

    fun fetchEntityAt(position: Position): Option<AnyGameEntity> {
        val (x, y) = position
        return if (x < 0 || y < 0 || y * stride + x >= entities.size) {
            none()
        } else {
            entities[y * stride + x]
        }
    }

    fun moveEntity(entity: AnyGameEntity, newPosition: Position): Option<Position> {
        val oldPosition = entity.position!!
        if (oldPosition == newPosition) {
            return none()
        }

        val (ox, oy) = oldPosition
        val (nx, ny) = newPosition
        entities[ny * stride + nx] = entities[oy * stride + ox]
        entities[oy * stride + ox] = none()

        return Some(newPosition)
    }
}

enum class InputMessage {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    PASS,
    USE,
}

enum class Direction {
    LEFT,
    RIGHT,
    UP,
    DOWN
}

data class GameContext(
    val world: World,
    var player: GameEntity<Player>,
    val command: InputMessage,
) : Context

typealias GameMessage = Message<GameContext>
typealias GameEntity<T> = Entity<T, GameContext>
typealias AnyGameEntity = GameEntity<EntityType>

suspend fun AnyGameEntity.tryActionsOn(context: GameContext, target: AnyGameEntity): Response {
    var result: Response = Pass
    findAttributeOrNull(EntityActions::class)?.let {
        it.createActionsFor(context, this, target).forEach { action ->
            if (target.receiveMessage(action) is Consumed) {
                result = Consumed
                return@forEach
            }
        }
    }
    return result
}

val AnyGameEntity.isImmovable
    get() = findAttribute(Immovable::class).isPresent

val AnyGameEntity.isPushable
    get() = findAttribute(Pushable::class).isPresent

fun <T : Attribute> AnyGameEntity.tryToFindAttribute(klass: KClass<T>): T = findAttribute(klass).orElseThrow {
    NoSuchElementException("Entity '$this' has no property with type '${klass.simpleName}'.")
}

var AnyGameEntity.position
    get() = tryToFindAttribute(EntityPosition::class).position
    set(value) {
        findAttribute(EntityPosition::class).map { it.position = value }
    }

val AnyGameEntity.interaction
    get() = tryToFindAttribute(Interaction::class).interaction

object Player : BaseEntityType(
    name = "player"
)

object Terrain : BaseEntityType(
    name = "terrain"
)

interface EntityAction<S : EntityType, T : EntityType> : GameMessage {
    val target: GameEntity<T>

    operator fun component1() = context
    operator fun component2() = source
    operator fun component3() = target
}

class EntityActions(
    private vararg val actions: KClass<out EntityAction<out EntityType, out EntityType>>
) : BaseAttribute() {
    fun createActionsFor(
        context: GameContext,
        source: GameEntity<EntityType>,
        target: GameEntity<EntityType>
    ): Iterable<EntityAction<out EntityType, out EntityType>> {
        return actions.map {
            try {
                it.constructors.first().call(context, source, target)
            } catch (e: Exception) {
                throw IllegalArgumentException("Can't create EntityAction. Does it have the proper constructor?")
            }
        }
    }
}

data class Position(
    val x: Int,
    val y: Int,
) {
    fun moveIn(direction: Direction): Position =
        when (direction) {
            Direction.LEFT -> Position(x - 1, y)
            Direction.RIGHT -> Position(x + 1, y)
            Direction.UP -> Position(x, y - 1)
            Direction.DOWN -> Position(x, y + 1)
        }
}

data class EntityPosition(
    var position: Position? = null
) : BaseAttribute()

data class Interact(
    override val context: GameContext,
    override val source: GameEntity<EntityType>
) : GameMessage

class Immovable : BaseAttribute()
class Pushable : BaseAttribute()
data class Interaction(
    val interaction: (GameContext, AnyGameEntity) -> GameMessage,
): BaseAttribute()
data class Group(
    val gid: Int
): BaseAttribute()

data class Move(
    override val context: GameContext,
    override val source: GameEntity<EntityType>,
    val direction: Direction,
) : GameMessage

class Movable : BaseFacet<GameContext, Move>(Move::class) {
    override suspend fun receive(message: Move): Response {
        val (context, source, direction) = message
        val world = context.world
        val position = source.position!!.moveIn(direction)

        var result = option {
            val oldEntity = world.fetchEntityAt(position).bind()
            if (oldEntity.isImmovable) {
                source.tryActionsOn(context, oldEntity)
            }

            if (oldEntity.isPushable) {
                val newMove = Move(context, oldEntity, direction)
                oldEntity.receiveMessage(newMove)
            }

            val newEntity = world.fetchEntityAt(position).bind()
            if (newEntity.isImmovable) {
                Consumed
            }
        }.getOrElse { Pass }

        if (result is Consumed) {
            return result
        }

        result = world.moveEntity(source, position).map { newPosition ->
            source.position = newPosition
            Consumed
        }.getOrElse { Pass }

        return result
    }
}

class Interactable : BaseFacet<GameContext, Interact>(Interact::class) {
    override suspend fun receive(message: Interact): Response {
        val (context, source) = message
        val interaction = source.interaction
        return source.receiveMessage(interaction(context, source))
    }
}

class Engine {
    init {

    }
}