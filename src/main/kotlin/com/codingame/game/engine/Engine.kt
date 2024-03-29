package com.codingame.game.engine

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import com.codingame.game.mapParser.readMap
import com.codingame.gameengine.module.entities.GraphicEntityModule
import com.codingame.gameengine.module.tooltip.TooltipModule
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
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.reflect.KClass
import com.codingame.gameengine.module.entities.World as GameEngineWorld
import com.codingame.gameengine.module.entities.*

private lateinit var worldModule: GameEngineWorld
private lateinit var graphicEntityModule: GraphicEntityModule

class World(
    private val stride: Int,
    private var entities: Array<ArrayList<AnyGameEntity>>,
    private var templateList: MutableMap<Int, EntityBuilder>
) {
    private var engine: Engine<GameContext> = Engine.create()
    private var entityCounter: Int = 0
    lateinit var spriteManager: SpriteManager

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
                if (e.hasTexture) {
                    e.spriteID = entityCounter++
                }
                engine.addEntity(e)
            }
        }
    }

    fun initSprites() {
        val height = worldModule.height
        val scale = height / (worldSize.second * 32.0)
        this.spriteManager = SpriteManager(graphicEntityModule, entities, stride, scale)
    }

    fun reloadManager(manager: SpriteManager) {
        this.spriteManager = manager
        manager.reloadMap(entities)
    }

    fun getGameContext(player: GameEntity<Player>, input: InputMessage): GameContext =
        GameContext(world = this, player = player, command = input)

    fun update(player: GameEntity<Player>, input: InputMessage) {
        val job = engine.start(getGameContext(player, input))
        runBlocking { job.join() }
    }

    fun fetchEntityAt(position: Position): Sequence<AnyGameEntity> {
        val (x, y) = position
        //System.err.println("fetchEntityAt(position=$position)")
        return if (x < 0 || y < 0 || y * stride + x >= entities.size) {
            emptySequence()
        } else {
            //System.err.println("Len: ${entities[y * stride + x].size}, position: $position")
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
            entity.spriteID = entityCounter++
        }
        spriteManager.allocateSprite(entity, position)
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
        spriteManager.freeSprite(entity)
        entity.position = Position(-1, -1)
        engine.removeEntity(entity)
    }

    fun moveEntity(entity: AnyGameEntity, newPosition: Position, curve: Curve): Boolean {
        val oldPosition = entity.position
        if (oldPosition == newPosition) {
            return false
        }

        val (ox, oy) = oldPosition
        val (nx, ny) = newPosition
        entities[ny * stride + nx].add(entity)
        entities[oy * stride + ox].removeIf { it === entity }

        entity.position = newPosition
        spriteManager.moveSprite(entity, newPosition, curve)
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
        for (idx in entities.indices) {
            val x = idx % stride
            val y = idx / stride
            spriteManager.setShadow(idx, !visibleBlocks.contains(Position(x, y)))
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

        @Suppress("NAME_SHADOWING")
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
        //System.err.println("Edit $id, mod: $mod")
        templateList[id]!!.modify(mod)
    }

    fun buildFromTemplate(id: Int): AnyGameEntity =
        templateList[id]!!.build()

    fun entities(): Sequence<AnyGameEntity> =
        entities.asSequence().flatMap { it }
}

class EntityBuilder(private val base: () -> AnyGameEntity) {
    private val modificationSequence = mutableListOf<Modification>()

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

    fun clone(): EntityBuilder {
        val newBuilder = EntityBuilder(base)
        newBuilder.modificationSequence.clear()
        newBuilder.modificationSequence.addAll(modificationSequence.toList())

        return newBuilder
    }

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
                    @Suppress("UNCHECKED_CAST")
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
    BUTTON_ON("button1_scaled.png"),
    BUTTON_OFF("button2_scaled_recolor.png"),
    FLOOR("floors/rect_gray*.png", 0),
    LAVA("lava/lava*.png"),
    WALLS("walls/wall_vines*.png"),
    CURTAIN("curtain_scaled.png", 4),
    WATER("water/dngn_shallow_water*.png"),
    FOG("fog.png")
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
        findAttribute(EntityTexture::class).map {
            it.texture = texture
        }
    }

var AnyGameEntity.spriteID
    get() = tryToFindAttribute(EntityTexture::class).spriteID
    set(num) {
        findAttribute(EntityTexture::class).map {
            it.spriteID = num
        }
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

val AnyGameEntity.interaction
    get() = tryToFindFacet(Interactable::class).interaction

val AnyGameEntity.steppableTarget
    get() = tryToFindFacet(Steppable::class).stepActionTarget

val AnyGameEntity.interactionCounter
    get() = tryToFindFacet(Interactable::class).interactionCounter

val AnyGameEntity.steppableCounter
    get() = tryToFindFacet(Steppable::class).stepActionCounter

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
) : BaseAttribute(), TooltipDescribe {
    override fun tooltipDescribe(ctx: GameContext): String =
        "Coordinates: ${position.x}, ${position.y}"
}

data class EntityTexture(
    var texture: Textures,
    var textureNum: Int = -1,
    var spriteID: Int = -1
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

interface TooltipDescribe {
    fun tooltipDescribe(ctx: GameContext): String
}

class Immovable : BaseAttribute(), TooltipDescribe {
    override fun tooltipDescribe(ctx: GameContext): String = "Immovable"
}

class Pushable : BaseAttribute(), TooltipDescribe {
    override fun tooltipDescribe(ctx: GameContext): String = "Pushable"
}

class WinPoint : BaseAttribute(), TooltipDescribe {
    override fun tooltipDescribe(ctx: GameContext): String = "Win point"
}

class VisionBlocker : BaseAttribute(), TooltipDescribe {
    override fun tooltipDescribe(ctx: GameContext): String = "Blocks vision"
}

data class Group(
    val gid: Int
) : BaseAttribute()

data class Template(
    val tid: Int
) : BaseAttribute(), TooltipDescribe {
    override fun tooltipDescribe(ctx: GameContext): String = "Group $tid"
}

object InputReceiver : BaseBehavior<GameContext>() {
    override suspend fun update(entity: AnyGameEntity, context: GameContext): Boolean {
        val (world, player, command) = context
        //System.err.println("InputReceiver: $entity")
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
                    world.spriteManager.swapButton(e)
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

class Killable(val spawnPoint: Option<Position> = None) : BaseFacet<GameContext, Kill>(Kill::class) {
    override suspend fun receive(message: Kill): Response {
        val (context, source) = message
        val (world, _, _) = context

        when (spawnPoint) {
            is Some -> world.moveEntity(source, spawnPoint.value, Curve.NONE)
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
        //System.err.println("Received message: $source position: ${source.position}")
        val world = context.world
        val position = source.position.moveIn(direction)

        for (entity in world.fetchEntityAt(position)) {
            if (entity.isImmovable) {
                source.tryActionsOn(context, entity)
            }

            if (entity.isPushable) {
                val newMove = Move(context, entity, direction)
                //System.err.println("Send message from $source")
                entity.receiveMessage(newMove)
            }

        }

        for (entity in world.fetchEntityAt(position)) {
            if (entity.isImmovable) {
                return Consumed
            }
        }

        return if (world.moveEntity(source, position, Curve.LINEAR)) {
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
    var interactionCounter: Int = 0
) : BaseFacet<GameContext, Interact>(Interact::class), TooltipDescribe {
    override suspend fun receive(message: Interact): Response {
        val (context, source) = message
        val world = context.world
        interactionCounter += 1
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
                        }
                        else -> throw Exception("Unreachable code")
                    }
                }

                if (interaction is ActionType.Modify) {
                    world.modifyTemplate(tid, interaction.mod)
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

    override fun tooltipDescribe(ctx: GameContext): String {
        val (_, player, _) = ctx

        val description = StringJoiner("\n")
        // FIXME: For now it can only be set to template
        val from = when (interactionTarget) {
            is ActionTarget.Template -> interactionTarget.tid
            else -> TODO()
        }

        when (interaction) {
            is ActionType.GameMessage -> {
                val msg = interaction.callBack(ctx, player)
                // FIXME: For now GameMessage can be only equal to Transmute, generalize it later
                description.add("Button - Transform")
                when (msg::class) {
                    Transmute::class -> {
                        val into = (msg as Transmute).into
                        description.add("Transform from: $from")
                        description.add("Transform into: $into")
                    }
                    else -> TODO()
                }
            }
            is ActionType.Modify -> {
                // FIXME: This should be moved to EntityBuilder
                // FIXME: For now it can only add/toggle facets and attributes, generalize it later
                description.add("Button - Modify")
                description.add("Target group: $from")
                when (interaction.mod) {
                    is EntityBuilder.AddAttribute -> {
                        val attribute = interaction.mod.callBack() as TooltipDescribe
                        description.add("Add <${attribute.tooltipDescribe(ctx)}>")
                    }
                    is EntityBuilder.AddFacet -> {
                        val facet = interaction.mod.callBack() as TooltipDescribe
                        description.add("Add <${facet.tooltipDescribe(ctx)}>")
                    }
                    is EntityBuilder.ToggleAttribute<*> -> {
                        val attribute = interaction.mod.callBack() as TooltipDescribe
                        description.add("Toggle: ${attribute.tooltipDescribe(ctx)}")
                    }
                    is EntityBuilder.ToggleFacet<*> -> {
                        val facet = interaction.mod.callBack() as TooltipDescribe
                        description.add("Toggle: ${facet.tooltipDescribe(ctx)}")
                    }
                    else -> TODO()
                }
            }
        }

        return description.toString()
    }
}

class Steppable(
    val stepAction: ActionType,
    val stepActionTarget: ActionTarget,
    var stepActionCounter: Int = 0,
) : BaseFacet<GameContext, StepOn>(StepOn::class), TooltipDescribe {
    override suspend fun receive(message: StepOn): Response {
        val (context, source) = message
        val world = context.world
        stepActionCounter += 1

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

                        }
                        else -> throw Exception("Unreachable code")
                    }
                }

                if (stepAction is ActionType.Modify) {
                    world.modifyTemplate(tid, stepAction.mod)
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

    override fun tooltipDescribe(ctx: GameContext): String {
        // FIXME: For now it is only used for spikes, generalize it later
        return "Trap"
    }
}

class Engine(mapPath: String, graphic: GraphicEntityModule, worldMod: GameEngineWorld, tooltips: TooltipModule) {
    private lateinit var world: World
    private lateinit var player: Entity<Player, GameContext>
    private var infoDisplay: InfoDisplay
    private var tooltips: TooltipModule
    private var resetCount: Int = 0
    private var visionRadius: Int = 4

    private val mapStride: Int
    private val mapTemplate: Array<ArrayList<EntityBuilder>>
    private val defaultTemplateList: Map<Int, EntityBuilder>

    val mapSize
        get() = world.worldSize
    val playerPosition
        get() = player.position

    init {
        graphicEntityModule = graphic
        worldModule = worldMod
        val (mapTemplate, stride, templateList) = readMap(mapPath)
        this.mapStride = stride
        this.mapTemplate = mapTemplate
        this.defaultTemplateList = templateList
        this.tooltips = tooltips

        buildWorld()
        world.initSprites()
        infoDisplay = InfoDisplay(graphicEntityModule, stride, 1080 / (world.worldSize.second * 32.0))
        updateVision()
        updateTooltips()
    }

    private fun buildWorld() {
        val map: Array<ArrayList<AnyGameEntity>> = Array(mapTemplate.size) { arrayListOf() }
        var playerEntity: GameEntity<Player>? = null
        for ((i, builderList) in mapTemplate.asSequence().withIndex()) {
            for (builder in builderList) {
                val entity = builder.build()
                if (entity.type == Player) {
                    @Suppress("UNCHECKED_CAST")
                    playerEntity = entity as GameEntity<Player>
                }
                map[i].add(entity)
            }
        }

        val templateList: MutableMap<Int, EntityBuilder> = mutableMapOf()
        for ((k, v) in defaultTemplateList) {
            templateList[k] = v.clone()
        }

        player = playerEntity!!
        world = World(mapStride, map, templateList)
    }

    fun reset() {
        val spriteManager = world.spriteManager
        buildWorld()
        world.reloadManager(spriteManager)
    }

    fun getVisibleEntities(all : Boolean): List<Pair<Position, List<String>>> {
        val positionList = mutableListOf<Pair<Position, MutableList<String>>>()
        var highestGroup: Array<IntArray> = Array(50) { IntArray(50) {0} }
        for (position in world.getVisibleBlocks(player.position, visionRadius)) {
            val entityList = mutableListOf<String>()
            val trimmedList = mutableListOf<String>()

            for (entity in world.fetchEntityAt(position)) {
                if (entity === player) {
                    continue
                }

                val entityDescription = StringJoiner(",")

                if (entity.hasTemplate) {
                    if (entity.tid > highestGroup[position.x][position.y]){
                        highestGroup[position.x][position.y] = entity.tid
                    }
                    entityDescription.add("OBJECT_TYPE:${entity.tid}")
                }

                if (entity.isWinPoint) {
                    entityDescription.add("WIN_POINT")
                }

                if (entity.isInteractable && entity.interactionTarget is ActionTarget.Template) {
                    highestGroup[position.x][position.y] = 9999
                    if (entity.interactionCounter == 0) {
                        entityDescription.add("INTERACT:?")
                    } else {
                        entityDescription.add("INTERACT:${(entity.interactionTarget as ActionTarget.Template).tid}")
                    }
                }

                if (entityDescription.length() > 0 && !(entity.hasTemplate && entity.tid == 0)){
                    trimmedList.add(entityDescription.toString())
                }
                if (entityDescription.length() > 0) {
                    entityList.add(entityDescription.toString())
                }
            }

            if (entityList.isNotEmpty()) {
                if (!all && highestGroup[position.x][position.y] > 0){
                    positionList.add(Pair(position, trimmedList))
                }
                else {
                    positionList.add(Pair(position, entityList))
                }
            }
        }

        return positionList
    }

    fun updateVision() {
        val (width, height) = mapSize
        val visibleCoords = getVisibleEntities(true).map { it.first }.toSet()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val id = y * width + x
                world.spriteManager.setShadow(id, !visibleCoords.contains(Position(x, y)))
            }
        }
    }

    // FIXME: Move this to SpriteManager or World
    fun setTooltip(entity: AnyGameEntity, tooltip: String) {
        when (val spriteEntity = world.spriteManager.getSpriteEntity(entity)) {
            is Some -> {
                tooltips.setTooltipText(spriteEntity.value, tooltip)
            }
        }
    }

    fun updateTooltips() {
        val dummyContext = world.getGameContext(player, InputMessage.PASS)
        var entityCheckList : Array<BooleanArray> = Array(50) { BooleanArray(50) {false} }
        val viewableProperties = arrayOf("Win point", "Immovable", "Trap", "Blocks vision")
        for (entity in world.entities()) {
            if (entity.hasTemplate && entity.tid < 1) {
                continue
            }

            val description = arrayListOf<String>()
            val ex = entity.position.x
            val ey = entity.position.y
            if (!entityCheckList[ex][ey]) {
                entityCheckList[ex][ey] = true
                description.add("X: $ex  Y: $ey")
                description.add("")
            }

            if (entity.type == Player) {
                description.add("Keke is here")
            }

            else if (entity.hasTexture && entity.texture == Textures.START) {
                description.add("Spawn point")
            }

            else if (entity.isInteractable) {
                val interactable = entity.facets.find { it is Interactable }!! as TooltipDescribe
                description.add(interactable.tooltipDescribe(dummyContext))
            }

            else if (entity.hasTemplate){
                description.add("Group ${entity.tid}")
                val properties = arrayListOf<String>()
                for (attribute in entity.attributes) {
                    if (attribute !is TooltipDescribe) {
                        continue
                    }
                    val attrText = (attribute as TooltipDescribe).tooltipDescribe(dummyContext)
                    if (attrText in viewableProperties){
                        properties.add("-$attrText")
                    }
                }

                for (facet in entity.facets) {
                    if (facet !is TooltipDescribe) {
                        continue
                    }
                    val attrText = (facet as TooltipDescribe).tooltipDescribe(dummyContext)
                    if (attrText in viewableProperties){
                        properties.add("-$attrText")
                    }
                }

                if (properties.size > 0) {
                    properties.sort()
                    description.add("Properties:")
                    description += properties
                }
                else {
                    description.add("No properties")
                }
            }

            when (val spriteEntity = world.spriteManager.getSpriteEntity(entity)) {
                is Some -> {
                    tooltips.setTooltipText(spriteEntity.value, description.joinToString("\n"))
                }
            }
        }
    }

    fun update(line: String, turn: Int, turnLimit: Int) {
        infoDisplay.setValue(InfoDisplay.DisplayText.SCORE, ceil(((turnLimit - turn).toDouble() * 100 / turnLimit)).toInt())

        if (line in sequenceOf("RE", "RESET")) {
            //System.err.println("Engine.update($line)")
            resetCount += 1
            infoDisplay.updateValue(InfoDisplay.DisplayText.RESETS, 1)
            reset()
            return
        }

        val action = when (line) {
            "LEFT" -> {
                infoDisplay.updateValue(InfoDisplay.DisplayText.STEPS_COUNT, 1)
                InputMessage.LEFT
            }
            "RIGHT" -> {
                infoDisplay.updateValue(InfoDisplay.DisplayText.STEPS_COUNT, 1)
                InputMessage.RIGHT
            }
            "UP" -> {
                infoDisplay.updateValue(InfoDisplay.DisplayText.STEPS_COUNT, 1)
                InputMessage.UP
            }
            "DOWN" -> {
                infoDisplay.updateValue(InfoDisplay.DisplayText.STEPS_COUNT, 1)
                InputMessage.DOWN
            }
            "PASS" -> InputMessage.PASS
            "USE" -> {
                infoDisplay.updateValue(InfoDisplay.DisplayText.INTERACT_COUNT, 1)
                InputMessage.USE
            }
            else -> {
                System.err.println("Invalid command $line")
                InputMessage.PASS
            }
        }

        //System.err.println("Engine.update($line)")
        world.update(player, action)
    }

    fun gameWon() = world.fetchEntityAt(player.position).any { it.isWinPoint }
    fun playerDied(): Boolean {
        val res = player.position == Position(-1, -1)
        if (res) {
            this.infoDisplay.updateValue(InfoDisplay.DisplayText.DEATHS, 1)
        }
        return res
    }
}
