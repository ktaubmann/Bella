package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AdtTextPoolTest {

	@Test
	void symbolsGetAMaxLength() {
		// without @MaxLength SAP answers 406 "Text elements contain errors"
		assertEquals("@MaxLength:19\n001=Deletable\n\n@MaxLength:50\n002=Enter at least one selection crit\n\n"
				+ "@MaxLength:17\n003=Longer than given\n",
				AdtTextPool.normalize("symbols", "001=Deletable\r\n\r\n@MaxLength:50\n002=Enter at least one selection crit\n"
						+ "@MaxLength:5\n003=Longer than given"));
		assertEquals(10, AdtTextPool.defaultMaxLength(0));
		assertEquals(29, AdtTextPool.defaultMaxLength(19));
		assertEquals(60, AdtTextPool.defaultMaxLength(40));
		assertEquals(132, AdtTextPool.defaultMaxLength(120));
		assertEquals(140, AdtTextPool.defaultMaxLength(140));
	}

	@Test
	void headingsAndSelectionsAsAdtSendsThem() {
		assertEquals("listHeader=Title\n\ncolumnHeader_1=Column\n",
				AdtTextPool.normalize("headings", "listHeader=Title\ncolumnHeader_1=Column"));
		// as ARC-1 writes them: names unpadded, one per line, each line ended
		assertEquals("P_TEST=Test run\nS_VBELN=Delivery\n",
				AdtTextPool.normalize("selections", "p_test  =Test run\r\n\r\nS_VBELN=Delivery"));
	}

	@Test
	void readBackIsCompared() {
		assertEquals(List.of(), AdtTextPool.differences("S_VBELN=Delivery\nP_TEST=Test",
				"P_TEST  =Test\r\n\r\nS_VBELN =Delivery"));
		assertEquals(List.of("S_VBELN: wrote 'Delivery', reads '?...'", "P_X: wrote 'x', reads nothing"),
				AdtTextPool.differences("S_VBELN=Delivery\nP_X=x", "S_VBELN =?..."));
		assertTrue(AdtTextPool.differences("@MaxLength:20\n001=A", "@MaxLength:30\r\n001=A\r\n").isEmpty());
	}

	@Test
	void textPoolOwners() {
		assertArrayEquals(new String[] { "CLAS", "ZCL_A" }, AdtClient.textPoolOwner("zcl_a=========CP"));
		assertArrayEquals(new String[] { "FUGR", "ZFG" }, AdtClient.textPoolOwner("SAPLZFG"));
		assertArrayEquals(new String[] { "PROG", "ZREP" }, AdtClient.textPoolOwner("ZREP"));
		assertArrayEquals(new String[] { "CLAS", "ZCL_A" },
				AdtClient.textPoolOwnerOfUri("/sap/bc/adt/textelements/classes/zcl_a"));
	}
}
