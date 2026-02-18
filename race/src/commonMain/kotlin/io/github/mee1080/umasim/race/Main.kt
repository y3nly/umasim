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

@Serializable
data class CliInput(
    val baseSetting: RaceSetting,
    val acquiredSkillIds: List<Int>,
    val unacquiredSkillIds: List<Int>,
    val iterations: Int = 100
)

suspend fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("{\"error\": \"No JSON payload provided\"}")
        return
    }

    val jsonParser = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
    }

    try {
        // 1. Download database
        loadSkillData()
        System.err.println("[DEBUG] Database loaded. Total skills: ${skillData2.size}")

        // 2. Decode payload
        val payload = jsonParser.decodeFromString<CliInput>(args[0])
        
        // 3. Run Math
        val results = calculateSkillDifferentials(
            payload.baseSetting,
            payload.acquiredSkillIds,
            payload.unacquiredSkillIds,
            payload.iterations
        )
        
        println(jsonParser.encodeToString(results))
        
    } catch (e: Exception) {
        System.err.println("{\"error\": \"Simulation failed: ${e.message}\"}")
    }
}

fun calculateSkillDifferentials(
    baseSetting: RaceSetting, 
    acquiredSkillIds: List<Int>, 
    unacquiredSkillIds: List<Int>, 
    iterations: Int
): Map<String, Double> {
    val calculator = RaceCalculator(SystemSetting())
    
    val acquiredStr = acquiredSkillIds.map { it.toString() }
    val unacquiredStr = unacquiredSkillIds.map { it.toString() }
    
    // Debug Log
    System.err.println("[DEBUG] Searching for acquired: $acquiredStr")
    System.err.println("[DEBUG] Searching for unacquired: $unacquiredStr")

    val baseSkills = skillData2.filter { skill: SkillData -> 
        acquiredStr.contains(skill.id.toString()) 
    }
    
    val baselineSetting = baseSetting.copy(
        umaStatus = baseSetting.umaStatus.copy(hasSkills = baseSkills)
    )
    
    // Debug Log
    System.err.println("[DEBUG] Found ${baseSkills.size} base skills.")

    val baseTimes = mutableListOf<Double>()
    for (i in 0 until iterations) {
        val simResult = calculator.simulate(baselineSetting)
        baseTimes.add(simResult.first.raceTime.toDouble())
    }
    val avgBaseTime = baseTimes.average()
    
    val differentials = mutableMapOf<String, Double>()
    
    val targetSkills = skillData2.filter { skill: SkillData -> 
        unacquiredStr.contains(skill.id.toString()) 
    }
    
    // Debug Log
    System.err.println("[DEBUG] Found ${targetSkills.size} target skills to test.")

    for (targetSkill in targetSkills) {
        val testSetting = baselineSetting.copy(
            umaStatus = baselineSetting.umaStatus.copy(hasSkills = baseSkills + targetSkill)
        )
        
        val testTimes = mutableListOf<Double>()
        for (i in 0 until iterations) {
            val simResult = calculator.simulate(testSetting)
            testTimes.add(simResult.first.raceTime.toDouble())
        }
        val avgTestTime = testTimes.average()
        
        differentials[targetSkill.name] = avgBaseTime - avgTestTime
    }
    
    return differentials
}