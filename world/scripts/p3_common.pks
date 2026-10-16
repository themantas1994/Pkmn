# Part 3 shared scripts: Pokémon Center nurse, marts, the Coral Isles ferry.

script p3_center_nurse
  faceplayer
  say "Welcome to the Pokémon Center!\pWe'll restore your Pokémon to full health."
  healcenter
  say "Your Pokémon are fighting fit. We hope to see you again!"
end

script p3_mart_ashfall
  faceplayer
  say "Welcome! Burn Heals are on special today."
  shop p3_ashfall
end

script p3_mart_frostmere
  faceplayer
  say "Brr! Come in, come in. Ice Heals are by the door."
  shop p3_frostmere
end

script p3_mart_dragonspire
  faceplayer
  say "Welcome to Dragonspire Mart. Only the finest for dragon tamers."
  shop p3_dragonspire
end

script p3_mart_league
  faceplayer
  say "Last chance to stock up before the Elite Four!"
  shop p3_league
end

script p3_mart_coral
  faceplayer
  say "Alola-- er, aloha! Welcome to the island shop."
  shop p3_coral
end

# Called by Part 1's tidewater_ferry sailor once the player is in the Hall of Fame.
script ferry_to_coral
  ask "SAILOR: The S.S. Corallia sails for the Coral Isles. All aboard?"
  if result yes
    say "SAILOR: Anchors aweigh!"
    fadeout
    music ferry
    wait 600
    warp coral_isles 9 17 up
    fadein
    restoremusic
    say "SAILOR: Welcome to the Coral Isles! Talk to me on the dock when you want to head home."
  else
    say "SAILOR: The sea will wait. Come back anytime."
  end
end

script ferry_to_tidewater
  faceplayer
  ask "SAILOR: Heading back to the mainland? We dock at any port you like."
  if result yes
    say "SAILOR: Pick a destination on the map!"
    fly
  else
    say "SAILOR: Enjoy the islands! The reef is gorgeous at dusk."
  end
end
