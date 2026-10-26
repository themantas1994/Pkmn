# Part 3A shared scripts: Pokémon Center nurse, marts.

script p3a_center_nurse
  faceplayer
  say "Welcome to the Pokémon Center!\pWe'll restore your Pokémon to full health."
  healcenter
  say "Your Pokémon are fighting fit. We hope to see you again!"
end

script p3a_mart_ashfall
  faceplayer
  say "Welcome! Burn Heals are on special today."
  shop p3a_ashfall
end

script p3a_mart_frostmere
  faceplayer
  say "Brr! Come in, come in. Ice Heals are by the door."
  shop p3a_frostmere
end

# Called by Part 1's tidewater_ferry sailor once the player is in the Hall of Fame.