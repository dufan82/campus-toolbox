package cn.campuslife.assistant.ui

import org.junit.Assert.*
import org.junit.Test

class BrandIdentityTest {
    @Test fun identityAndFourColorsMatchApprovedReference() {
        assertEquals("校伴",Brand.name)
        assertEquals("CAMPUS COMPANION",Brand.english)
        assertEquals("校园效率工具箱",Brand.positioning)
        assertEquals("让校园生活更简单",Brand.tagline)
        assertEquals(0xff172b49.toInt(),Brand.navy)
        assertEquals(0xff35b6a0.toInt(),Brand.teal)
        assertEquals(0xfff2a65a.toInt(),Brand.orange)
        assertEquals(0xfff5f7fa.toInt(),Brand.mist)
    }
    @Test fun repositoryAddressDoesNotChangeWithProductName() {
        assertEquals("https://github.com/dufan82/campus-toolbox",Brand.github)
    }
}
