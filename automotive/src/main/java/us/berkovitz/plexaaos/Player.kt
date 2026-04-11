package us.berkovitz.plexaaos

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@UnstableApi
class PlexPlayer(player: Player): ForwardingSimpleBasePlayer(player) {

}