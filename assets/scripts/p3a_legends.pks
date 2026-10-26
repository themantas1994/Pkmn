# Part 3A post-game legendary encounters (Ho-Oh, Terapagos), shown only with flag hall_of_fame. Each one is one-time once caught; if it is defeated or the player
# runs, it stays in place (the shadow NPC is hidden only by its p3a_<name>_done flag, set on capture).

script p3a_leg_hooh
  cry hooh
  say "A Pokémon with rainbow wings descends onto the roof!"
  wildbattle hooh 70 norun
  if result caught
    setflag p3a_hooh_done
    say "Ho-Oh's sacred flame will light your journey."
  else
    say "Ho-Oh circles the tower and settles again, watching you."
  end
end

script p3a_leg_terapagos
  cry terapagos
  say "The crystals pulse in rainbow colors... A tiny turtle shining with Tera light!"
  wildbattle terapagos 70 norun
  if result caught
    setflag p3a_terapagos_done
    say "Terapagos's shell glows. Its Tera Type is Stellar!"
  else
    say "Terapagos withdrew into the crystal again."
  end
end
