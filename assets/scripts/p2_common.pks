# Part 2 shared scripts: Pokémon Center nurses and Poké Mart clerks.

script p2_nurse
  faceplayer
  say "Welcome to the Pokémon Center! Let me restore your Pokémon to full health."
  healcenter
  say "Your Pokémon are fighting fit. We hope to see you again!"
end

script p2_shop_lumen
  faceplayer
  say "Welcome! The ruins are dusty, so stock up on Repels."
  shop p2_lumen
end

script p2_shop_quarrytown
  faceplayer
  say "Welcome! Miners' prices for miners' supplies."
  shop p2_quarrytown
end

script p2_shop_seraphine
  faceplayer
  say "Welcome! Mist-cured goods, the finest in Tessera."
  shop p2_seraphine
end

script p2_shop_voltara_1f
  faceplayer
  say "Welcome to the Voltara Department Store! Everyday supplies are on this floor."
  shop p2_voltara_1f
end

script p2_shop_voltara_2f
  faceplayer
  say "Evolution corner! Stones, trade gear, and the famous Linking Cord."
  shop p2_voltara_evo
end

script p2_shop_voltara_tm
  faceplayer
  say "TM counter! Teach your team something shocking."
  shop p2_voltara_tm
end
