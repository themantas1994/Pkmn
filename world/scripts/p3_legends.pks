# Post-game legendary encounters. Each one is one-time once caught; if it is defeated or the player
# runs, it stays in place (the shadow NPC is hidden only by its p3_<name>_done flag, set on capture).

script leg_hooh
  cry hooh
  say "A Pokémon with rainbow wings descends onto the roof!"
  wildbattle hooh 70 norun
  if result caught
    setflag p3_hooh_done
    say "Ho-Oh's sacred flame will light your journey."
  else
    say "Ho-Oh circles the tower and settles again, watching you."
  end
end

script leg_groudon
  if not item redorb
    say "Something enormous is sleeping in the magma... It doesn't stir."
  else
    say "The Red Orb blazes! The sleeping giant opens its eyes!"
    cry groudon
    shake
    wildbattle groudon 70 norun
    if result caught
      setflag p3_groudon_done
      say "Groudon calmed down. Let it hold the Red Orb to awaken its Primal power!"
    else
      say "Groudon sinks back into the magma, glowing faintly..."
    end
  end
end

script leg_moltres
  cry moltres
  say "A blazing bird spreads its wings in the heat!"
  wildbattle moltres 65 norun
  if result caught
    setflag p3_moltres_done
  else
    say "Moltres flares and returns to its perch."
  end
end

script leg_entei
  cry entei
  say "A volcanic beast roars! The ground shakes!"
  wildbattle entei 65 norun
  if result caught
    setflag p3_entei_done
  else
    say "Entei vanished in a burst of heat... but its tracks lead back here."
  end
end

script leg_suicune
  cry suicune
  say "A beautiful Pokémon glides across the water!"
  wildbattle suicune 65 norun
  if result caught
    setflag p3_suicune_done
  else
    say "Suicune bounded away across the waves... It seems to return to this spot."
  end
end

script leg_raikou
  cry raikou
  say "Thunder cracks! A lightning-fast beast blocks the way!"
  wildbattle raikou 65 norun
  if result caught
    setflag p3_raikou_done
  else
    say "Raikou vanished in a flash, but static still crackles here."
  end
end

script leg_articuno
  cry articuno
  say "A freezing wind blows... An icy bird appears!"
  wildbattle articuno 65 norun
  if result caught
    setflag p3_articuno_done
  else
    say "Articuno drifted back up to the icy ledge."
  end
end

script leg_zapdos
  cry zapdos
  say "Lightning splits the storm clouds! Zapdos screeches!"
  wildbattle zapdos 65 norun
  if result caught
    setflag p3_zapdos_done
  else
    say "Zapdos returned to the heart of the storm."
  end
end

script leg_lugia
  cry lugia
  shake
  say "The sea parts... A silver guardian rises from the waves!"
  wildbattle lugia 70 norun
  if result caught
    setflag p3_lugia_done
    say "The storm over Tempest Isle begins to clear."
  else
    say "Lugia dove beneath the waves. The storm still swirls overhead."
  end
end

script leg_kyogre
  if not item blueorb
    say "A colossal shape slumbers in the deep water. It doesn't respond."
  else
    say "The Blue Orb glows! The sea churns as the giant awakens!"
    cry kyogre
    shake
    wildbattle kyogre 70 norun
    if result caught
      setflag p3_kyogre_done
      say "Kyogre calmed down. Let it hold the Blue Orb to awaken its Primal power!"
    else
      say "Kyogre sank back into the dark water..."
    end
  end
end

script leg_rayquaza
  cry rayquaza
  shake
  say "A great green dragon coils at the summit of the spire!"
  wildbattle rayquaza 70 norun
  if result caught
    setflag p3_rayquaza_done
    say "Rayquaza can Mega Evolve if it knows Dragon Ascent... and you hold a Key Stone."
  else
    say "Rayquaza soared into the clouds, then circled back to its perch."
  end
end

script leg_mewtwo
  cry mewtwo
  say "......\pA powerful psychic presence fills your mind. It wants to test you."
  wildbattle mewtwo 70 norun
  if result caught
    setflag p3_mewtwo_done
  else
    say "Mewtwo teleported away... You can still feel it watching from the depths."
  end
end

script leg_solgaleo
  cry solgaleo
  say "The altar blazes like the noon sun! A radiant beast descends!"
  wildbattle solgaleo 70 norun
  if result caught
    setflag p3_solgaleo_done
    call leg_ultra_check
  else
    say "The sunlight fades... The beast will return when the altar shines again."
  end
end

script leg_lunala
  cry lunala
  say "The altar fills with moonlight! A great winged beast unfolds its wings!"
  wildbattle lunala 70 norun
  if result caught
    setflag p3_lunala_done
    call leg_ultra_check
  else
    say "Moonlight dims... The beast will return when the altar glows again."
  end
end

# After either Solgaleo or Lunala is caught, the altar keeper hands over Ultranecrozium Z.
script leg_ultra_check
  if not flag p3_got_ultranecroziumz
    say "A crystal shard falls from the sky and lands at your feet..."
    giveitem ultranecroziumz
    setflag p3_got_ultranecroziumz
    say "Ultranecrozium Z! With Necrozma fused to the light of the sun or moon, it can unleash Ultra Burst!"
  end
end

script leg_terapagos
  cry terapagos
  say "The crystals pulse in rainbow colors... A tiny turtle shining with Tera light!"
  wildbattle terapagos 70 norun
  if result caught
    setflag p3_terapagos_done
    say "Terapagos's shell glows. Its Tera Type is Stellar!"
  else
    say "Terapagos withdrew into the crystal again."
  end
end

script leg_eternatus
  say "The den is flooded with crimson Dynamax energy!"
  cry eternatus
  shake
  wildbattle eternatus 72 norun
  if result caught
    setflag p3_eternatus_done
    say "The crimson light dims. The den is quiet at last."
  else
    say "Eternatus retreated into the swirling energy at the den's heart."
  end
end

# Necrozma: story battle at Prism Summit (see p3_summit.pks). If it was not caught there,
# it reappears at the summit after the Hall of Fame.
script leg_necrozma_return
  cry necrozma
  say "The prismatic light gathers again... Necrozma has returned to the summit!"
  wildbattle necrozma 65 norun
  if result caught
    setflag p3_necrozma_caught
    say "Necrozma's light is calm now."
  else
    say "Necrozma dissolved into light. It will gather itself here again."
  end
end
