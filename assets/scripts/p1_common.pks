# Part 1 shared scripts: Pokémon Center nurse, Mart clerks.

script p1_nurse
  faceplayer
  say "Welcome to the Pokémon Center!"
  ask "Shall we restore your Pokémon to full health?"
  if result yes
    say "OK, may I see your Pokémon?"
    healcenter
    say "Thank you for waiting.\pYour Pokémon are fighting fit! We hope to see you again!"
  else
    say "We hope to see you again!"
  end
end

script p1_fernwick_clerk
  faceplayer
  say "Welcome! How may I help you?"
  shop fernwick_mart
  say "Please come again!"
end

script p1_brindle_clerk
  faceplayer
  say "Welcome to Brindle Mart!"
  if badges >= 1
    shop brindle_mart_badge
  else
    shop brindle_mart
  end
  say "Please come again!"
end

script p1_tidewater_clerk
  faceplayer
  say "Ahoy! Welcome to the harbor Mart!"
  shop tidewater_mart
  say "Fair winds!"
end
