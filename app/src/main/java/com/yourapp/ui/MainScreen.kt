@Composable
private fun MidiStatusBar(
    midiStatus: String,
    midiOutEnabled: Boolean,
    onConnect: () -> Unit,
    onToggleMidiOut: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (midiStatus.startsWith("No")) "⚪" else "🟢", fontSize = 14.sp)
                Text(
                    "  MIDI: $midiStatus",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Toggle MIDI OUT
                Button(
                    onClick = onToggleMidiOut,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (midiOutEnabled)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                        contentColor = if (midiOutEnabled)
                            MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (midiOutEnabled) "📤 OUT: ON" else "📤 OUT",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // Connect
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Connect", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}