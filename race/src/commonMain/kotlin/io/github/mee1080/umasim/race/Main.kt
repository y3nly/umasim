package io.github.mee1080.umasim.race

import io.github.mee1080.umasim.race.calc2.RaceSetting
import io.github.mee1080.umasim.race.calc2.RaceCalculator
import io.github.mee1080.umasim.race.calc2.SystemSetting
import io.github.mee1080.umasim.race.data2.SkillData
import io.github.mee1080.umasim.race.data2.skillData2
import io.github.mee1080.umasim.race.data2.loadSkillData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.*
import java.io.PrintStream
import java.io.FileOutputStream
import java.io.FileDescriptor
import java.nio.charset.StandardCharsets

@Serializable
data class CliInput(
    val baseSetting: RaceSetting,
    val acquiredSkillIds: List<Int>,
    val unacquiredSkillIds: List<Int>,
    val iterations: Int = 100
)

@Serializable
data class CliOutput(
    val baselineTimes: List<Double>,
    val candidateResults: Map<String, List<Double>>
)

suspend fun main(args: Array<String>) {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8.name()))

    if (args.isEmpty()) return

    val jsonParser = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
    }

    try {
        loadSkillData()
        val payload = jsonParser.decodeFromString<CliInput>(args[0])
        
        val results = runSimulation(
            payload.baseSetting,
            payload.acquiredSkillIds,
            payload.unacquiredSkillIds,
            payload.iterations
        )
        
        println(jsonParser.encodeToString(results))
        
    } catch (e: Exception) {
        System.err.println("{\"error\": \"${e.message}\"}")
    }
}

suspend fun runSimulation(
    baseSetting: RaceSetting, 
    acquiredSkillIds: List<Int>, 
    unacquiredSkillIds: List<Int>, 
    iterations: Int
): CliOutput = coroutineScope {
    
    val calculator = RaceCalculator(SystemSetting())
    
    val acquiredStr = acquiredSkillIds.map { it.toString() }
    val unacquiredStr = unacquiredSkillIds.map { it.toString() }
    
    val baseSkills = skillData2.filter { acquiredStr.contains(it.id) }
    
    val baselineSetting = baseSetting.copy(
        umaStatus = baseSetting.umaStatus.copy(hasSkills = baseSkills)
    )

    // Run Baseline
    val baselineTimes = (0 until iterations).map {
        async(Dispatchers.Default) { 
            calculator.simulate(baselineSetting).first.raceTime.toDouble() 
        }
    }.awaitAll()
    
    val results = mutableMapOf<String, List<Double>>()
    
    val candidateSkills = skillData2.filter { unacquiredStr.contains(it.id) }

    // Run Candidates
    for (candidate in candidateSkills) {
        val testSetting = baselineSetting.copy(
            umaStatus = baselineSetting.umaStatus.copy(hasSkills = baseSkills + candidate)
        )
        
        val testTimes = (0 until iterations).map {
            async(Dispatchers.Default) {
                calculator.simulate(testSetting).first.raceTime.toDouble()
            }
        }.awaitAll()
        
        results[candidate.name] = testTimes
    }
    
    return@coroutineScope CliOutput(baselineTimes, results)
}