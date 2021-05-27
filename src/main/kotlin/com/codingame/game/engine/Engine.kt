package com.codingame.game.engine

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import com.codingame.gameengine.module.entities.Curve
import com.codingame.gameengine.module.entities.GraphicEntityModule
import com.codingame.gameengine.module.entities.Sprite
import kotlinx.coroutines.runBlocking
import org.hexworks.amethyst.api.*
import org.hexworks.amethyst.api.Engine
import org.hexworks.amethyst.api.base.BaseAttribute
import org.hexworks.amethyst.api.base.BaseBehavior
import org.hexworks.amethyst.api.base.BaseEntityType
import org.hexworks.amethyst.api.base.BaseFacet
import org.hexworks.amethyst.api.builder.EntityBuilder
import org.hexworks.amethyst.api.entity.Entity
import org.hexworks.amethyst.api.entity.EntityType
import java.nio.file.NoSuchFileException
import java.util.*
import kotlin.math.absoluteValue
import kotlin.reflect.KClass

private lateinit var graphicEntityModule: GraphicEntityModule

class World(private val stride: Int, private var entities: Array<ArrayList<AnyGameEntity>>) {
    private var engine: Engine<GameContext> = Engine.create()
    private var visionBlocks: Array<Sprite>

    init {
        entities.forEachIndexed { idx, el ->
            val x = idx % stride
            val y = idx / stride
            for (e in el) {
                e.position = Position(x, y)
                // XXX: Gigacheat
                e.findAttributeOrNull(EntityTexture::class)?.apply {
                    e.texture = texture
                }
                engine.addEntity(e)
            }
        }

        visionBlocks = Array(entities.size) { idx ->
            val x = idx % stride
            val y = idx / stride

            graphicEntityModule.createSprite().apply {
                image = "semi_transparent.png"
                setX(x * 32, Curve.NONE)
                setY(y * 32, Curve.NONE)
                isVisible = true
            }

        }
    }

    fun update(player: GameEntity<Player>, input: InputMessage) {
        val job = engine.start(
            GameContext(
                world = this,
                player = player,
                command = input
            )
        )
        runBlocking { job.join() }
    }

    fun fetchEntityAt(position: Position): Sequence<AnyGameEntity> {
        val (x, y) = position
        System.err.println("fetchEntityAt(position=$position)")
        return if (x < 0 || y < 0 || y * stride + x >= entities.size) {
            emptySequence()
        } else {
            System.err.println("Len: ${entities[y * stride + x].size}, position: $position")
            ArrayList(entities[y * stride + x]).asSequence()
        }
    }

    fun addEntity(entity: AnyGameEntity, position: Position) {
        val idx = position.y * stride + position.x
        if (idx < 0 || idx > entities.size) {
            return
        }

        entity.position = position
        engine.addEntity(entity)
        entities[idx].add(entity)
    }

    fun removeEntity(entity: AnyGameEntity) {
        if (entity.hasPosition) {
            val (x, y) = entity.position
            val idx = y * stride + x

            if (idx > 0 && idx < entities.size) {
                entities[idx].removeIf { it === entity }
            }
        }

        engine.removeEntity(entity)
    }

    fun moveEntity(entity: AnyGameEntity, newPosition: Position): Boolean {
        val oldPosition = entity.position
        if (oldPosition == newPosition) {
            return false
        }

        val (ox, oy) = oldPosition
        val (nx, ny) = newPosition
        entities[ny * stride + nx].add(entity)
        entities[oy * stride + ox].removeIf { it === entity }

        entity.position = newPosition
        entity.sprite?.setX(newPosition.x * 32, Curve.NONE)
        entity.sprite?.setY(newPosition.y * 32, Curve.NONE)

        return true
    }

    fun getVisibleBlocks(startPosition: Position, visionRadius: Int): Sequence<Position> {
        val visibleBlocks = mutableSetOf(startPosition)
        for (dy in sequenceOf(-1, 1)) {
            for (dx in sequenceOf(-1, 1)) {
                castLightAt(
                    startPosition,
                    visionRadius,
                    visibleBlocks,
                    1,
                    1.0f, 0.0f,
                    0, dx, dy, 0
                )
                castLightAt(
                    startPosition,
                    visionRadius,
                    visibleBlocks,
                    1,
                    1.0f, 0.0f,
                    dx, 0, 0, dy
                )
            }
        }

        visionBlocks.forEachIndexed { idx, e ->
            val x = idx % stride
            val y = idx / stride
            e.isVisible = !visibleBlocks.contains(Position(x, y))
        }

        return visibleBlocks.asSequence()
    }

    private fun castLightAt(
        startPosition: Position,
        visionRadius: Int,
        visibleBlocks: MutableSet<Position>,
        currentRow: Int,
        start: Float,
        end: Float,
        xx: Int, xy: Int, yx: Int, yy: Int
    ) {
        val width = stride
        val height = entities.size / stride
        var start = start // NOTE(MarWit): Shadowed because Kotlin is dumb

        var newStart = 0.0f
        if (start < end) {
            return
        }

        var isVisible = true
        for (distance in currentRow..visionRadius) {
            if (!isVisible) {
                break
            }

            val dy = -distance
            innerLoop@ for (dx in -distance..0) {
                val currentX = startPosition.x + dx * xx + dy * xy
                val currentY = startPosition.y + dx * yx + dy * yy
                val currentPos = Position(currentX, currentY)
                val leftSlope = (dx - 0.5f) / (dy + 0.5f)
                val rightSlope = (dx + 0.5f) / (dy - 0.5f)

                if (!(currentX >= 0 && currentY >= 0 && currentX < width && currentY < height) || start < rightSlope) {
                    continue
                } else if (end > leftSlope) {
                    break
                }

                val manDistance = dx.absoluteValue + dy.absoluteValue
                if (manDistance <= visionRadius) {
                    visibleBlocks.add(currentPos)
                }

                if (!isVisible) {
                    for (entity in fetchEntityAt(currentPos)) {
                        if (entity.blocksVision) {
                            newStart = rightSlope
                            continue@innerLoop
                        }
                    }

                    isVisible = true
                    start = newStart
                } else {
                    for (entity in fetchEntityAt(currentPos)) {
                        if (entity.blocksVision && distance < visionRadius) {
                            isVisible = false
                            castLightAt(
                                startPosition, visionRadius, visibleBlocks,
                                distance + 1, start, leftSlope, xx, xy, yx, yy
                            )
                            newStart = rightSlope
                            break
                        }
                    }
                }
            }
        }
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

val AnyGameEntity.isWinPoint
    get() = findAttribute(WinPoint::class).isPresent

val AnyGameEntity.isInteractable
    get() = findFacet(Interactable::class).isPresent

val AnyGameEntity.blocksVision
    get() = findAttribute(VisionBlocker::class).isPresent

val AnyGameEntity.hasGroup
    get() = findAttribute(Group::class).isPresent

val AnyGameEntity.hasPosition
    get() = findAttribute(EntityPosition::class).isPresent


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
        val pos = tryToFindAttribute(EntityPosition::class).position
        findAttribute(EntityTexture::class).map {
            it.texture = texture
            var textureLoc = texture.filepath
            val i = textureLoc.indexOf('*')
            if (i >= 0) {
                if (it.texture_num == -1) {
                    throw NoSuchFileException("You need to specify texture number before loading it!")
                }
                textureLoc = textureLoc.replace("*", it.texture_num.toString())
            }
            it.sprite = graphicEntityModule.createSprite().apply {
                this.image = textureLoc
                this.x = pos.x * 32
                this.y = pos.y * 32
            }
        }
    }

var AnyGameEntity.sprite
    get() = tryToFindAttribute(EntityTexture::class).sprite
    set(value) {
        findAttribute(EntityTexture::class).map { it.sprite = value }
    }

var AnyGameEntity.texture_num
    get() = tryToFindAttribute(EntityTexture::class).texture_num
    set(value) {
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

fun <T : EntityType> newGameEntityOfType(
    type: T,
    init: EntityBuilder<T, GameContext>.() -> Unit
) = newEntityOfType(type, init)

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
    var position: Position = Position(-1, -1)
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
class WinPoint : BaseAttribute()
class VisionBlocker : BaseAttribute()
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
        System.err.println("InputReceiver: $entity")
        when (command) {
            InputMessage.DOWN -> player.receiveMessage(Move(context, player, Direction.DOWN))
            InputMessage.UP -> player.receiveMessage(Move(context, player, Direction.UP))
            InputMessage.LEFT -> player.receiveMessage(Move(context, player, Direction.LEFT))
            InputMessage.RIGHT -> player.receiveMessage(Move(context, player, Direction.RIGHT))
            InputMessage.PASS -> player.receiveMessage(Move(context, player, Direction.NONE))
            InputMessage.USE -> {
                val position = player.position
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

data class Kill(
    override val context: GameContext,
    override val source: AnyGameEntity,
    val spawnPoint: Option<Position>
) : GameMessage

class Killable : BaseFacet<GameContext, Kill>(Kill::class) {
    override suspend fun receive(message: Kill): Response {
        val (context, source, spawnPoint) = message
        val (world, _, _) = context

        when (spawnPoint) {
            is Some -> world.moveEntity(source, spawnPoint.value)
            None -> world.removeEntity(source)
        }

        return Consumed
    }
}

data class Move(
    override val context: GameContext,
    override val source: AnyGameEntity,
    val direction: Direction,
) : GameMessage

class Movable : BaseFacet<GameContext, Move>(Move::class) {
    override suspend fun receive(message: Move): Response {
        val (context, source, direction) = message
        System.err.println("Received message: $source position: ${source.position}")
        val world = context.world
        val position = source.position.moveIn(direction)

        for (entity in world.fetchEntityAt(position)) {
            if (entity.isImmovable) {
                source.tryActionsOn(context, entity)
            }

            if (entity.isPushable) {
                val newMove = Move(context, entity, direction)
                System.err.println("Send message from $source")
                entity.receiveMessage(newMove)
            }
        }

        for (entity in world.fetchEntityAt(position)) {
            if (entity.isImmovable) {
                return Consumed
            }
        }

        return if (world.moveEntity(source, position)) {
            Consumed
        } else {
            Pass
        }
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

class Engine(graphic: GraphicEntityModule) {
    private var world: World
    private var player: Entity<Player, GameContext>
    var visionRadius: Int = 1

    init {
        graphicEntityModule = graphic

        val wall = {
            arrayListOf<AnyGameEntity>(newGameEntityOfType(Terrain) {
                attributes(
                    Immovable(),
                    VisionBlocker(),
                    EntityTexture(texture = Textures.WALLS, texture_num = 0),
                    EntityPosition()
                )
            })
        }
        val floor = {
            arrayListOf<AnyGameEntity>(newGameEntityOfType(Terrain) {
                attributes(EntityTexture(texture = Textures.FLOOR, texture_num = 0), EntityPosition())
            })
        }
        val box = {
            arrayListOf<AnyGameEntity>(
                floor()[0],
                newGameEntityOfType(Terrain) {
                    attributes(
                        Immovable(),
                        VisionBlocker(),
                        Pushable(),
                        EntityTexture(texture = Textures.BOX),
                        EntityPosition()
                    )
                    facets(Movable())
                })
        }
        player =
            newGameEntityOfType(Player) {
                attributes(EntityTexture(texture = Textures.KEKE), EntityPosition())
                facets(Movable())
                behaviors(InputReceiver)
            }
        val keke = arrayListOf<AnyGameEntity>(floor()[0], player)
        val winPoint = arrayListOf<AnyGameEntity>(
            floor()[0],
            newGameEntityOfType(Terrain) {
                attributes(WinPoint(), EntityTexture(texture = Textures.FINISH), EntityPosition())
            },
            box()[1]
        )

        val map = arrayOf<ArrayList<AnyGameEntity>>(
            wall(), wall(), wall(), wall(), wall(), wall(), wall(), wall(),
            wall(), floor(), floor(), floor(), floor(), floor(), floor(), wall(),
            wall(), floor(), floor(), floor(), floor(), floor(), floor(), wall(),
            wall(), floor(), floor(), floor(), floor(), wall(), wall(), wall(),
            wall(), floor(), floor(), winPoint, box(), floor(), keke, wall(),
            wall(), floor(), wall(), floor(), wall(), floor(), floor(), wall(),
            wall(), floor(), wall(), floor(), wall(), floor(), floor(), wall(),
            wall(), wall(), wall(), wall(), wall(), wall(), wall(), wall(),
        )
        world = World(8, map)
    }

    fun getVisibleEntities(): List<Pair<Position, List<String>>> {
        val positionList = mutableListOf<Pair<Position, MutableList<String>>>()

        for (position in world.getVisibleBlocks(player.position, visionRadius + 1)) {
            val entityList = mutableListOf<String>()

            for (entity in world.fetchEntityAt(position)) {
                val entityDescription = StringJoiner(",")

                if (entity.isImmovable) {
                    entityDescription.add("IMMOVABLE")
                }

                if (entity.isPushable) {
                    entityDescription.add("PUSHABLE")
                }

                if (entity.isWinPoint) {
                    entityDescription.add("WIN_POINT")
                }

                if (entity.hasGroup) {
                    entityDescription.add("GROUP:${entity.gid}")
                }

                if (entity.isInteractable) {
                    entityDescription.add("INTERACT:${entity.interactionGroup}")
                }

                if (entity.blocksVision) {
                    entityDescription.add("VISION_BLOCKER")
                }

                if (entityDescription.length() > 0) {
                    entityList.add(entityDescription.toString())
                }
            }

            if (entityList.isNotEmpty()) {
                positionList.add(Pair(position, entityList))
            }
        }

        return positionList
    }

    fun update(line: String) {
        val action = when (line) {
            "LEFT" -> InputMessage.LEFT
            "RIGHT" -> InputMessage.RIGHT
            "UP" -> InputMessage.UP
            "DOWN" -> InputMessage.DOWN
            "PASS" -> InputMessage.PASS
            "USE" -> InputMessage.USE
            else -> {
                System.err.println("Invalid command $line")
                InputMessage.PASS
            }
        }

        System.err.println("Engine.update($line)")
        world.update(player, action)
    }

    fun gameWon() = world.fetchEntityAt(player.position).any { it.isWinPoint }
}