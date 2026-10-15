# Owner PDTA metadata fixture

export.zip is the exact owner-supplied YamahaArranger_SF2_PDTA_Export.zip, SHA-256 15c7c1f1d4786fd549076f6b62339810514fbd8a613bc2348d8db0461cac2875.

It contains four LIST/pdta payloads, raw name tables and zone attenuation TSVs, plus extraction manifest/README. It contains no complete SF2, sdta/sample PCM, or audio. The native parser and independent host parser verify all four golden payloads and all derived rows. The whole-file SHA-256 fields are audited provenance, not independently rehashed whole SF2 files on this host. No runtime catalog activation follows from this fixture.
