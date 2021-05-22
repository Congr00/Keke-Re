package com.codingame.game.engine

import arrow.core.*
import com.codingame.gameengine.module.entities.GraphicEntityModule
import com.codingame.gameengine.module.entities.Sprite
import org.hexworks.amethyst.api.*
import org.hexworks.amethyst.api.base.BaseAttribute
import org.hexworks.amethyst.api.base.BaseBehavior
import org.hexworks.amethyst.api.base.BaseEntityType
import org.hexworks.amethyst.api.base.BaseFacet
import org.hexworks.amethyst.api.entity.Entity
import org.hexworks.amethyst.api.entity.EntityType
import java.nio.file.NoSuchFileException
import kotlin.reflect.KClass

private lateinit var graphicEntityModule: GraphicEntityModule

class World(private val stride: Int, private var entities: Array<ArrayList<AnyGameEntity>>) {
    fun fetchEntityAt(position: Position): Sequence<AnyGameEntity> {
        val (x, y) = position
        return if (x < 0 || y < 0 || y * stride + x >= entities.size) {
            emptySequence()
        } else {
            entities[y * stride + x].asSequence()
        }
    }

    fun moveEntity(entity: AnyGameEntity, newPosition: Position): Option<Position> {
        val oldPosition = entity.position!!
        if (oldPosition == newPosition) {
            return none()
        }

        val (ox, oy) = oldPosition
        val (nx, ny) = newPosition
        entities[ny * stride + nx].add(entity)
        entities[oy * stride + ox].removeIf { it === entity }

        return Some(newPosition)
    }

    fun entities(): Sequence<AnyGameEntity> =
        entities.asSequence().flatMap { it }
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
    DOWN,
    NONE
}

enum class Textures(val filepath: String) {
    // texture paths, * means number (from 0 to N)
    KEKE("keke.png"),
    BOX("box.png"),
    FINISH("finish.png"),
    SPIKE("spike.png"),
    START("start.png"),
    WINDOW("window.png"),
    BUTTON_ON("btc_on.png"),
    BUTTON_OFF("btn_off.png"),
    FLOOR("floors/rect_gray*.png"),
    LAVA("lava/lava*.png"),
    WALLS("walls/wall_vines*.png"),
    WATER("water/dngn_shallow_water*.png")
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

val AnyGameEntity.hasGroup
    get() = findAttribute(Group::class).isPresent

fun <T : Attribute> AnyGameEntity.tryToFindAttribute(klass: KClass<T>): T = findAttribute(klass).orElseThrow {
    NoSuchElementException("Entity '$this' has no property with type '${klass.simpleName}'.")
}

var AnyGameEntity.position
    get() = tryToFindAttribute(EntityPosition::class).position
    set(value) {
        findAttribute(EntityPosition::class).map { it.position = value }
    }

var AnyGameEntity.texture
    get() = tryToFindAttribute(EntityTexture::class).texture
    set(texture) {
        // we require to set position first before setting texture
        val pos = tryToFindAttribute(EntityPosition::class).position!!
        findAttribute(EntityTexture::class).map {
            it.texture = texture
            var textureLoc = texture.toString()
            val n = textureLoc.indexOf('*')
            if (n > 0) {
                if (it.texture_num == -1) {
                    throw NoSuchFileException("You need to specify texture number before loading it!")
                }
                textureLoc = textureLoc.replace('*', it.texture_num.toChar())
            }
            it.sprite = graphicEntityModule.createSprite().apply {
                this.image = textureLoc
                this.x = pos.x
                this.y = pos.y
            }
        }
    }

var AnyGameEntity.sprite
    get() = tryToFindAttribute(EntityTexture::class).sprite
    set(value){
        findAttribute(EntityTexture::class).map { it.sprite = value }
    }

var AnyGameEntity.texture_num
    get() = tryToFindAttribute(EntityTexture::class).texture_num
    set(value){
        findAttribute(EntityTexture::class).map { it.texture_num = value }
    }

val AnyGameEntity.interaction
    get() = tryToFindAttribute(Interaction::class).interaction

val AnyGameEntity.interactionGroup
    get() = tryToFindAttribute(Interaction::class).interaction_group

val AnyGameEntity.gid
    get() = tryToFindAttribute(Group::class).gid

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
            Direction.NONE -> Position(x, y)
        }
}

data class EntityPosition(
    var position: Position? = null
) : BaseAttribute()

data class EntityTexture(
    var texture: Textures,
    var sprite: Sprite? = null,
    var texture_num: Int = -1
) : BaseAttribute()

data class Interact(
    override val context: GameContext,
    override val source: GameEntity<EntityType>
) : GameMessage

class Immovable : BaseAttribute()
class Pushable : BaseAttribute()
data class Interaction(
    val interaction: (GameContext, AnyGameEntity) -> GameMessage,
    val interaction_group: Option<Int>,
) : BaseAttribute()

data class Group(
    val gid: Int
) : BaseAttribute()

object InputReceiver : BaseBehavior<GameContext>() {
    override suspend fun update(entity: AnyGameEntity, context: GameContext): Boolean {
        val (world, player, command) = context
        when (command) {
            InputMessage.DOWN -> player.receiveMessage(Move(context, player, Direction.DOWN))
            InputMessage.UP -> player.receiveMessage(Move(context, player, Direction.UP))
            InputMessage.LEFT -> player.receiveMessage(Move(context, player, Direction.LEFT))
            InputMessage.RIGHT -> player.receiveMessage(Move(context, player, Direction.RIGHT))
            InputMessage.PASS -> player.receiveMessage(Move(context, player, Direction.NONE))
            InputMessage.USE -> {
                val position = player.position!!
                for (e in world.fetchEntityAt(position)) {
                    if (e === player) {
                        continue
                    }

                    e.receiveMessage(Interact(context, player))
                }
            }
        }

        return true
    }
}

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

        for (entity in world.fetchEntityAt(position)) {
            if (entity.isImmovable) {
                source.tryActionsOn(context, entity)
            }

            if (entity.isPushable) {
                val newMove = Move(context, entity, direction)
                entity.receiveMessage(newMove)
            }
        }

        for (entity in world.fetchEntityAt(position)) {
            if (entity.isImmovable) {
                return Consumed
            }
        }

        return world.moveEntity(source, position).map { newPosition ->
            source.position = newPosition
            if (source.sprite != null) {
                source.sprite!!.x = newPosition.x
                source.sprite!!.y = newPosition.y
            }
            Consumed
        }.getOrElse { Pass }
    }
}

class Interactable : BaseFacet<GameContext, Interact>(Interact::class) {
    override suspend fun receive(message: Interact): Response {
        val (context, source) = message
        val world = context.world
        val interaction = source.interaction

        return when (val interactionGroup = source.interactionGroup) {
            is Some -> {
                val gid = interactionGroup.value

                for (e in world.entities().filter { it.hasGroup && it.gid == gid }) {
                    e.receiveMessage(interaction(context, e))
                }

                Consumed
            }
            None -> source.receiveMessage(interaction(context, source))
        }
    }
}

class Engine {
    init {

    }
}