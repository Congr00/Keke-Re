package com.codingame.game.engine

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import com.codingame.game.mapParser.readMap
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
import org.hexworks.amethyst.api.entity.Entity
import org.hexworks.amethyst.api.entity.EntityType
import org.hexworks.amethyst.api.entity.MutableEntity
import org.hexworks.amethyst.api.extensions.FacetWithContext
import org.hexworks.amethyst.api.system.Facet
import java.nio.file.NoSuchFileException
import java.util.*
import kotlin.math.absoluteValue
import kotlin.reflect.KClass

private lateinit var graphicEntityModule: GraphicEntityModule

class World(
    private val stride: Int,
    private var entities: Array<ArrayList<AnyGameEntity>>,
    private var templateList: MutableMap<Int, EntityBuilder>
) {
    private var engine: Engine<GameContext> = Engine.create()
    private var visionBlocks: Array<Sprite>

    val worldSize: Pair<Int, Int>
        get() {
            val size = entities.size
            return Pair(stride, size / stride)
        }

    init {
        entities.forEachIndexed { idx, el ->
            val x = idx % stride
            val y = idx / stride
            for (e in el) {
                e.position = Position(x, y)
                // XXX: Gigacheat
                if (e.hasTexture) {
                    e.texture = e.texture
                }
                engine.addEntity(e)
            }
        }

        visionBlocks = Array(entities.size) { idx ->
            val x = idx % stride
            val y = idx / stride

            graphicEntityModule.createSprite().apply {
                image = "semi_transparent.png"
                setX(x * 64, Curve.NONE)
                setY(y * 64, Curve.NONE)
                setScale(2.0)
                isVisible = true
                zIndex = 4
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
        if (entity.hasTexture) {
            entity.texture = entity.texture
        }

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
        if (entity.hasTexture) {
            entity.sprite?.setX(newPosition.x * 64, Curve.NONE)
            entity.sprite?.setY(newPosition.y * 64, Curve.NONE)
        }

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

    fun modifyTemplate(id: Int, mod: EntityBuilder.Modification) {
        templateList[id]?.modify(mod)
    }

    fun buildFromTemplate(id: Int): AnyGameEntity =
        templateList[id]!!.build()

    fun entities(): Sequence<AnyGameEntity> =
        entities.asSequence().flatMap { it }
}

class EntityBuilder(private val base: () -> AnyGameEntity) {
    private val modificationSequence = arrayListOf<Modification>()

    abstract class Modification
    class AddAttribute(val callBack: () -> BaseAttribute) : Modification()
    class AddFacet(val callBack: () -> FacetWithContext<GameContext>) : Modification()
    class RemoveAttribute<T : Attribute>(val klass: KClass<T>) : Modification()
    class RemoveFacet<T : FacetWithContext<GameContext>>(val klass: KClass<T>) : Modification()
    class ModifyAttribute<T : Attribute>(val klass: KClass<T>, val callBack: (T).() -> Unit) : Modification()
    class ToggleAttribute<T : Attribute>(val klass: KClass<T>, val callBack: () -> BaseAttribute) : Modification()
    class ToggleFacet<T : FacetWithContext<GameContext>>(
        val klass: KClass<T>,
        val callBack: () -> FacetWithContext<GameContext>
    ) : Modification()

    fun modify(mod: Modification) {
        modificationSequence.add(mod)
    }

    fun build(): AnyGameEntity {
        val entity = base().asMutableEntity()
        modificationSequence.forEach { mod -> performSingle(entity, mod) }
        return entity
    }

    companion object {
        fun <T : EntityType> performSingle(target: MutableEntity<T, GameContext>, mod: Modification) {
            when (mod) {
                is AddAttribute -> target.addAttribute(mod.callBack())
                is AddFacet -> target.addFacet(mod.callBack())
                is RemoveAttribute<*> -> {
                    val toRemove = target.attributes.find { it.javaClass == mod.klass.java }
                    target.removeAttribute(toRemove!!)
                }
                is RemoveFacet<*> -> {
                    val toRemove = target.facets.find { it.javaClass == mod.klass.java }
                    target.removeFacet(toRemove!!)
                }
                is ModifyAttribute<*> -> {
                    val func = mod.callBack as ((Attribute).() -> Unit)
                    target.findAttribute(mod.klass).ifPresent { func(it) }
                }
                is ToggleAttribute<*> -> {
                    val exists = target.attributes.find { it.javaClass == mod.klass.java }
                    if (exists !== null) {
                        target.removeAttribute(exists)
                    } else {
                        target.addAttribute(mod.callBack())
                    }
                }
                is ToggleFacet<*> -> {
                    val exists = target.facets.find { it.javaClass == mod.klass.java }
                    if (exists !== null) {
                        target.removeFacet(exists)
                    } else {
                        target.addFacet(mod.callBack())
                    }
                }
            }
        }
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
    DOWN,
    NONE
}

enum class Textures(val filepath: String, val zIndex: Int = 1) {
    // texture paths, * means number (from 0 to N)
    KEKE("keke.png", 3),
    BOX("box.png", 2),
    FINISH("finish.png"),
    SPIKE("spike.png"),
    START("start.png"),
    WINDOW("window.png"),
    BUTTON_ON("btn_on.png"),
    BUTTON_OFF("btn_off.png"),
    FLOOR("floors/rect_gray*.png", 0),
    LAVA("lava/lava*.png"),
    WALLS("walls/wall_vines*.png"),
    CURTAIN("curtain.png", 4),
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

val AnyGameEntity.isSteppable
    get() = findFacet(Steppable::class).isPresent

val AnyGameEntity.blocksVision
    get() = findAttribute(VisionBlocker::class).isPresent

val AnyGameEntity.hasTexture
    get() = findAttribute(EntityTexture::class).isPresent

val AnyGameEntity.hasGroup
    get() = findAttribute(Group::class).isPresent

val AnyGameEntity.hasTemplate
    get() = findAttribute(Template::class).isPresent

val AnyGameEntity.hasPosition
    get() = findAttribute(EntityPosition::class).isPresent


fun <T : Attribute> AnyGameEntity.tryToFindAttribute(klass: KClass<T>): T = findAttribute(klass).orElseThrow {
    NoSuchElementException("Entity '$this' has no property with type '${klass.simpleName}'.")
}

fun <T : Facet<GameContext, *>> AnyGameEntity.tryToFindFacet(klass: KClass<T>): T = findFacet(klass).orElseThrow {
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
                if (it.textureNum == -1) {
                    throw NoSuchFileException("You need to specify texture number before loading it!")
                }
                textureLoc = textureLoc.replace("*", it.textureNum.toString())
            }
            it.sprite = graphicEntityModule.createSprite().apply {
                image = textureLoc
                setX(pos.x * 64, Curve.NONE)
                setY(pos.y * 64, Curve.NONE)
                setScale(2.0)
                zIndex = texture.zIndex
            }
        }
    }

var AnyGameEntity.sprite
    get() = tryToFindAttribute(EntityTexture::class).sprite
    set(value) {
        findAttribute(EntityTexture::class).map { it.sprite = value }
    }

var AnyGameEntity.textureNum
    get() = tryToFindAttribute(EntityTexture::class).textureNum
    set(value) {
        findAttribute(EntityTexture::class).map { it.textureNum = value }
    }

val AnyGameEntity.gid
    get() = tryToFindAttribute(Group::class).gid

val AnyGameEntity.tid
    get() = tryToFindAttribute(Template::class).tid

val AnyGameEntity.interactionTarget
    get() = tryToFindFacet(Interactable::class).interactionTarget

val AnyGameEntity.steppableTarget
    get() = tryToFindFacet(Steppable::class).stepActionTarget

object Player : BaseEntityType(
    name = "player"
)

object Terrain : BaseEntityType(
    name = "terrain"
)

fun <T : EntityType> newGameEntityOfType(
    type: T,
    init: org.hexworks.amethyst.api.builder.EntityBuilder<T, GameContext>.() -> Unit
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
    var textureNum: Int = -1
) : BaseAttribute()

data class Interact(
    override val context: GameContext,
    override val source: GameEntity<EntityType>
) : GameMessage

data class Transmute(
    override val context: GameContext,
    override val source: GameEntity<EntityType>,
    val into: Int,
) : GameMessage

data class StepOn(
    override val context: GameContext,
    override val source: GameEntity<EntityType>
) : GameMessage


class Immovable : BaseAttribute()
class Pushable : BaseAttribute()
class WinPoint : BaseAttribute()
class VisionBlocker : BaseAttribute()

data class Group(
    val gid: Int
) : BaseAttribute()

data class Template(
    val tid: Int
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
    override val source: AnyGameEntity
) : GameMessage

class Killable(val spawnPoint: Option<Position>) : BaseFacet<GameContext, Kill>(Kill::class) {
    override suspend fun receive(message: Kill): Response {
        val (context, source) = message
        val (world, _, _) = context

        when (spawnPoint) {
            is Some -> world.moveEntity(source, spawnPoint.value)
            None -> world.removeEntity(source)
        }

        return Consumed
    }
}

class Transmutable : BaseFacet<GameContext, Transmute>(Transmute::class) {
    override suspend fun receive(message: Transmute): Response {
        val (context, source, template) = message
        val (world, _, _) = context

        val newEntity = world.buildFromTemplate(template)
        val oldPosition = source.position

        world.removeEntity(source)
        world.addEntity(newEntity, oldPosition)

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
            for (entity in world.fetchEntityAt(position)) {
                entity.receiveMessage(StepOn(context, source))
            }

            Consumed
        } else {
            Pass
        }
    }
}

abstract class ActionType {
    class GameMessage(val callBack: (GameContext, AnyGameEntity) -> com.codingame.game.engine.GameMessage) :
        ActionType()

    class Modify(val mod: EntityBuilder.Modification) : ActionType()
}

abstract class ActionTarget {
    object Self : ActionTarget()
    class Group(val gid: Int) : ActionTarget()
    class Template(val tid: Int) : ActionTarget()
}

class Interactable(
    val interaction: ActionType,
    val interactionTarget: ActionTarget,
) : BaseFacet<GameContext, Interact>(Interact::class) {
    override suspend fun receive(message: Interact): Response {
        val (context, source) = message
        val world = context.world

        return when (interactionTarget) {
            is ActionTarget.Group -> {
                val gid = interactionTarget.gid
                for (e in world.entities().filter { it.hasGroup && it.gid == gid }) {
                    when (interaction) {
                        is ActionType.GameMessage -> {
                            e.receiveMessage(interaction.callBack(context, e))
                        }
                        is ActionType.Modify -> {
                            EntityBuilder.performSingle(e.asMutableEntity(), interaction.mod)
                        }
                        else -> throw Exception("Unreachable code")
                    }
                }

                Consumed
            }
            is ActionTarget.Template -> {
                val tid = interactionTarget.tid
                for (e in world.entities().filter { it.hasTemplate && it.tid == tid }) {
                    when (interaction) {
                        is ActionType.GameMessage -> {
                            e.receiveMessage(interaction.callBack(context, e))
                        }
                        is ActionType.Modify -> {
                            EntityBuilder.performSingle(e.asMutableEntity(), interaction.mod)
                            world.modifyTemplate(tid, interaction.mod)
                        }
                        else -> throw Exception("Unreachable code")
                    }
                }

                Consumed
            }
            is ActionTarget.Self -> {
                when (interaction) {
                    is ActionType.GameMessage -> {
                        source.receiveMessage(interaction.callBack(context, source))
                    }
                    is ActionType.Modify -> {
                        EntityBuilder.performSingle(source.asMutableEntity(), interaction.mod)
                    }
                    else -> throw Exception("Unreachable code")
                }

                Consumed
            }
            else -> throw Exception("Unreachable code")
        }
    }
}

class Steppable(
    val stepAction: ActionType,
    val stepActionTarget: ActionTarget,
) : BaseFacet<GameContext, StepOn>(StepOn::class) {
    override suspend fun receive(message: StepOn): Response {
        val (context, source) = message
        val world = context.world

        return when (stepActionTarget) {
            is ActionTarget.Group -> {
                val gid = stepActionTarget.gid
                for (e in world.entities().filter { it.hasGroup && it.gid == gid }) {
                    when (stepAction) {
                        is ActionType.GameMessage -> {
                            e.receiveMessage(stepAction.callBack(context, e))
                        }
                        is ActionType.Modify -> {
                            EntityBuilder.performSingle(e.asMutableEntity(), stepAction.mod)
                        }
                        else -> throw Exception("Unreachable code")
                    }
                }

                Consumed
            }
            is ActionTarget.Template -> {
                val tid = stepActionTarget.tid
                for (e in world.entities().filter { it.hasTemplate && it.tid == tid }) {
                    when (stepAction) {
                        is ActionType.GameMessage -> {
                            e.receiveMessage(stepAction.callBack(context, e))
                        }
                        is ActionType.Modify -> {
                            EntityBuilder.performSingle(e.asMutableEntity(), stepAction.mod)
                            world.modifyTemplate(tid, stepAction.mod)

                        }
                        else -> throw Exception("Unreachable code")
                    }
                }

                Consumed
            }
            is ActionTarget.Self -> {
                when (stepAction) {
                    is ActionType.GameMessage -> {
                        source.receiveMessage(stepAction.callBack(context, source))
                    }
                    is ActionType.Modify -> {
                        EntityBuilder.performSingle(source.asMutableEntity(), stepAction.mod)
                    }
                    else -> throw Exception("Unreachable code")
                }

                Consumed
            }
            else -> throw Exception("Unreachable code")
        }
    }
}

class Engine(graphic: GraphicEntityModule) {
    private var world: World
    private var player: Entity<Player, GameContext>
    private var visionRadius: Int = 2

    val mapSize
        get() = world.worldSize

    init {
        graphicEntityModule = graphic
        val (map, stride, playerEntity, templateList) = readMap("maps/World1/map2.tmx")
        player = playerEntity
        world = World(stride, map, templateList)
    }

    fun getVisibleEntities(): List<Pair<Position, List<String>>> {
        val positionList = mutableListOf<Pair<Position, MutableList<String>>>()

        for (position in world.getVisibleBlocks(player.position, visionRadius)) {
            val entityList = mutableListOf<String>()

            for (entity in world.fetchEntityAt(position)) {
                if (entity === player) {
                    continue
                }

                val entityDescription = StringJoiner(",")

                if (entity.hasTemplate) {
                    entityDescription.add("OBJECT_TYPE:${entity.tid}")
                }

                if (entity.isWinPoint) {
                    entityDescription.add("WIN_POINT")
                }

                if (entity.isInteractable && entity.interactionTarget is ActionTarget.Template) {
                    entityDescription.add("INTERACT:${(entity.interactionTarget as ActionTarget.Template).tid}")
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