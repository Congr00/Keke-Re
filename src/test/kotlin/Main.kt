import com.codingame.gameengine.runner.SoloGameRunner

fun main(args: Array<String>) {
    val gameRunner = SoloGameRunner()
    // gameRunner.setAgent(Agent::class.java)
    gameRunner.setAgent("/bin/bash /home/marwit/agent.sh")
    gameRunner.setTestCaseInput("0\n0\n0\n0\n0")
    gameRunner.start()
}