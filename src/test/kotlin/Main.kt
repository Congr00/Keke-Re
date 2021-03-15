import com.codingame.gameengine.runner.MultiplayerGameRunner

fun main(args: Array<String>) {
    var gameRunner = MultiplayerGameRunner()
    gameRunner.addAgent(Agent1::class.java)
    gameRunner.addAgent(Agent2::class.java)
    gameRunner.start()
}