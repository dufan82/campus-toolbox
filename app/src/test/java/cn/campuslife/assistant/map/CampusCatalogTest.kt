package cn.campuslife.assistant.map

import org.junit.Assert.*
import org.junit.Test

class CampusCatalogTest {
    @Test fun campusGatesAndTrainingEntrancesDoNotShareSearchTargets() {
        val queries=mapOf("北门" to "campus-gate-north","校区北门" to "campus-gate-north","北" to "campus-gate-north",
            "南门" to "campus-gate-south","校区南门" to "campus-gate-south","南" to "campus-gate-south",
            "实训一北门" to "training-1-north","实训1楼北门" to "training-1-north","实训1北" to "training-1-north",
            "实训一南门" to "training-1-south","实训1楼南门" to "training-1-south","实训1南" to "training-1-south")
        queries.forEach { (query,id) -> assertEquals("Unexpected matches for $query",listOf(id),CampusCatalog.search(query,MapCategory.ALL).map { it.id }) }
        assertEquals("实训1楼北门",CampusCatalog.search("实训一北门",MapCategory.ALL).single().name)
        assertEquals("实训1楼南门",CampusCatalog.search("实训一南门",MapCategory.ALL).single().name)
    }

    @Test fun campusGateNeverInheritsTheCoordinateOfATrainingEntrance() {
        val point=GeoPoint(25.5501,119.7514)
        val located=CampusCatalog.located(CampusCatalog.search("北门",MapCategory.ALL)+CampusCatalog.search("实训1北",MapCategory.ALL),mapOf("training-1-north" to point))
        assertEquals(listOf("campus-gate-north","training-1-north"),located.map { it.landmark.id })
        assertNull(located[0].point)
        assertEquals(point,located[1].point)
    }

    @Test fun studentDormSearchUsesReadableShortNamesWithoutChangingCoordinateIds() {
        val point=GeoPoint(25.5526,119.751)
        for(query in listOf("生1","学生宿舍一号楼","学生宿舍1栋","学生公寓一栋","1栋","一号楼","１号楼","第1宿舍楼")) {
            val results=CampusCatalog.located(CampusCatalog.search(query,MapCategory.ALL),mapOf("dorm-1" to point))
            assertEquals("Unexpected matches for $query",listOf("dorm-1"),results.map { it.landmark.id })
            assertEquals("1号楼",results.single().landmark.name)
            assertEquals(point,results.single().point)
        }
    }

    @Test fun tenthDormHasTheSameAliasesAndNeverMatchesTheFirstDorm() {
        for(query in listOf("生十","学生宿舍十号楼","十栋","学生公寓10栋","宿舍楼10")) {
            val result=CampusCatalog.search(query,MapCategory.DORM)
            assertEquals("Unexpected matches for $query",listOf("dorm-10"),result.map { it.id })
            assertEquals("10号楼",result.single().name)
        }
    }

    @Test fun teacherDormsHaveUnambiguousFullNamesAndCommonSearchTerms() {
        for(query in listOf("师1","师一","教师公寓1号楼","教师公寓一栋","教师宿舍一号楼","教工宿舍1号楼")) {
            val result=CampusCatalog.search(query,MapCategory.ALL)
            assertEquals("Unexpected matches for $query",listOf("teacher-1"),result.map { it.id })
            assertEquals("教师公寓1号楼",result.single().name)
        }
        assertEquals(listOf("dorm-1"),CampusCatalog.search("1号楼",MapCategory.ALL).map { it.id })
    }

    @Test fun trainingBuildingsUseBuildingSuffixAndKeepTheirOldAbbreviations() {
        for(query in listOf("实训2楼","实训二号楼","实2","实训楼二","2号实训楼","实训2栋")) {
            val result=CampusCatalog.search(query,MapCategory.STUDY)
            assertEquals("Unexpected matches for $query",listOf("training-2"),result.map { it.id })
            assertEquals("实训2楼",result.single().name)
        }
        assertEquals("实训8楼",CampusCatalog.search("实训八",MapCategory.ALL).single().name)
        assertTrue(CampusCatalog.search("实训二楼",MapCategory.DORM).isEmpty())
    }

    @Test fun dormAliasesAndBareNumbersFindTheSameBuildingNotAnEatery() {
        for(query in listOf("2","生2","2号楼","2号宿舍","学生公寓2","二号宿舍楼","学生公寓2号楼")) {
            assertEquals(listOf("dorm-2"),CampusCatalog.search(query,MapCategory.ALL).map { it.id })
        }
        assertEquals(listOf("dorm-1"),CampusCatalog.search("1",MapCategory.DORM).map { it.id })
        assertFalse(CampusCatalog.search("1",MapCategory.DORM).any { it.id=="dorm-10" })
    }

    @Test fun foodAliasesFindCanteensButUnlistedShopsNeverEnterCampusResults() {
        assertEquals(listOf("canteen-2"),CampusCatalog.search("二食堂",MapCategory.ALL).map { it.id })
        assertTrue(CampusCatalog.search("炒面",MapCategory.ALL).isEmpty())
        assertTrue(CampusCatalog.search("壶见",MapCategory.ALL).isEmpty())
        assertEquals(listOf("coffee"),CampusCatalog.search("书语",MapCategory.ALL).map { it.id })
    }

    @Test fun departmentNamesResolveToTheirTeachingBuildings() {
        assertEquals(listOf("training-1-south"),CampusCatalog.search("数字产业学院",MapCategory.ALL).map { it.id })
        assertEquals(setOf("training-6","training-7"),CampusCatalog.search("文化创意",MapCategory.STUDY).map { it.id }.toSet())
        assertTrue(CampusCatalog.search("建工馆",MapCategory.DORM).isEmpty())
    }

    @Test fun categoriesKeepAllRequestedPlacesIncludingAllDormTypes() {
        assertEquals(45,CampusCatalog.search("",MapCategory.ALL).size)
        assertEquals(13,CampusCatalog.search("",MapCategory.STUDY).size)
        assertEquals(14,CampusCatalog.search("",MapCategory.DORM).size)
        assertEquals(2,CampusCatalog.search("",MapCategory.FOOD).size)
        assertEquals(listOf("sunken-plaza"),CampusCatalog.search("下沉",MapCategory.ALL).map { it.id })
        assertEquals(45,CampusCatalog.entries.map { it.id }.distinct().size)
    }

    @Test fun whitespaceChineseNumbersAndLongQueriesCannotLeakIntoOnlineSearch() {
        assertEquals(listOf("dorm-2"),CampusCatalog.search("  二号宿舍 \n",MapCategory.ALL).map { it.id })
        assertEquals(listOf("science-4"),CampusCatalog.search("工科四",MapCategory.ALL).map { it.id })
        assertTrue(CampusCatalog.search("楼".repeat(500),MapCategory.ALL).isEmpty())
    }

    @Test fun unresolvedPlacesHaveNoInventedCoordinateOrNavigationTarget() {
        val entries=CampusCatalog.search("生2",MapCategory.ALL)
        assertNull(CampusCatalog.located(entries,emptyMap()).single().point)
        assertNull(CampusCatalog.located(entries,mapOf("other" to GeoPoint(25.5,119.8))).single().point)
        val point=GeoPoint(25.5,119.8)
        assertEquals(point,CampusCatalog.located(entries,mapOf("dorm-2" to point)).single().point)
    }
}
