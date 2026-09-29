                    rv.getOrNull(2)?.let { v -> SxVoiceCard("RIGHT 3", v.displayName(), v.enabled, Modifier.weight(1f).clickable { onPickRightVoice(2) }) }
                    SxVoiceCard("LEFT", "OFF", false, Modifier.weight(1f))
                }
                Row(
                    Modifier.fillMaxWidth().height(if (compact) 48.dp else 56.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SxValue("CHORD", state.detectedChordLabel.ifBlank { "—" }, Modifier.weight(1f), null, null, compact)
                    SxValue("TEMPO", state.tempoBpm.toString(), Modifier.weight(.8f), vm::onTempoDown, vm::onTempoUp, compact)
                    SxValue("TRANSPOSE", if (state.transpose >= 0) "+${state.transpose}" else state.transpose.toString(), Modifier.weight(.9f), vm::onTransposeDown, vm::onTransposeUp, compact)
                    SxValue("RELEASE", state.releaseTime.toString(), Modifier.weight(.8f), vm::onReleaseDown, vm::onReleaseUp, compact)
                    SxValue("SPLIT", state.splitPoint, Modifier.weight(.8f), null, null, compact)
                    Surface(color = Color(0xFF172029), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1.25f).fillMaxHeight().border(1.dp, Color(0xFF34404C), RoundedCornerShape(3.dp))) {
                        Row(Modifier.fillMaxSize().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("PART", color = SxDim, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                            listOf("A", "B", "C", "D").forEach { part -> Box(Modifier.weight(1f).fillMaxHeight().padding(start = 2.dp).background(if (state.activeSection.endsWith(" $part")) SxOrangeBright else Color(0xFF26313B), RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) { Text(part, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold) } }