# Part 3C: legendary dens and their keepers. Every legendary is a static `wildbattle ... norun`.
# Caught -> flag p3c_caught_<species> (hides it for good). Defeated -> p3c_met_<species> and it vanishes until the
# map is re-entered, so a defeated legend can be retried. Ho-Oh and Terapagos belong to Part 3A.
# Note: `result` must be read right after `wildbattle` (message boxes reset it).

# ================================================================ Birds, Silver Wing and Lugia
script p3c_articuno
  say "An icy bird rests on the iceberg. Its breath hangs in the air like diamond dust."
  cry articuno
  wildbattle articuno 70 norun
  if result caught
    setflag p3c_caught_articuno
    setflag p3c_met_articuno
    hide articuno
  elif result defeated
    setflag p3c_met_articuno
    say "Articuno scattered into a flurry of snow... It may return."
    hide articuno
  end
  call p3c_birds_check
end

script p3c_zapdos
  say "Lightning crashes on the islet! A crackling bird spreads its wings!"
  cry zapdos
  wildbattle zapdos 70 norun
  if result caught
    setflag p3c_caught_zapdos
    setflag p3c_met_zapdos
    hide zapdos
  elif result defeated
    setflag p3c_met_zapdos
    say "Zapdos vanished in a flash of lightning... It may return."
    hide zapdos
  end
  call p3c_birds_check
end

script p3c_moltres
  say "The summit blazes! A bird of living flame shrieks at the intruder!"
  cry moltres
  wildbattle moltres 70 norun
  if result caught
    setflag p3c_caught_moltres
    setflag p3c_met_moltres
    hide moltres
  elif result defeated
    setflag p3c_met_moltres
    say "Moltres soared into the smoke... It may return."
    hide moltres
  end
  call p3c_birds_check
end

script p3c_birds_check
  if flag p3c_met_articuno and flag p3c_met_zapdos and flag p3c_met_moltres and not flag p3c_birds_told
    setflag p3c_birds_told
    say "Somewhere far to the north, a storm seems to stir..."
  end
end

script p3c_storm_sage
  faceplayer
  if flag p3c_got_silverwing
    say "STORM SAGE: The Silver Wing will calm the gale in Tempest Cave. Lugia waits below, Champion."
  elif flag p3c_met_articuno and flag p3c_met_zapdos and flag p3c_met_moltres
    say "STORM SAGE: Ice, thunder and fire... you have faced all three! The birds' master will surely accept you.\pTake this feather. It fell from the guardian of the sea a hundred years ago."
    giveitem silverwing
    setflag p3c_got_silverwing
    if not quest p3c_birds started
      quest p3c_birds start
    end
    quest p3c_birds 1
    say "STORM SAGE: The gale at the bottom of Tempest Cave bows to that feather. Go!"
  else
    say "STORM SAGE: This storm is the breath of Lugia, guardian of the seas, sleeping beneath our feet.\pLegend says three birds serve it: ice on the northern iceberg, thunder on the Tower Isle islet, fire on Cinder Isle's summit.\pFace all three, and I will give you what you need to reach their master."
    if not quest p3c_birds started
      quest p3c_birds start
    end
  end
end

script p3c_gale
  if item silverwing
    setflag p3c_gale_calmed
    say "A furious gale roars up from the ladder...\pThe Silver Wing shines, and the wind falls silent."
  else
    say "A furious gale roars up from the ladder! You can't get any closer."
    move player down
  end
end

script p3c_lugia
  say "The lagoon stirs. A silver giant rises from the water, eyes calm and ancient."
  cry lugia
  wildbattle lugia 72 norun
  if result caught
    setflag p3c_caught_lugia
    setflag p3c_met_lugia
    hide lugia
    quest p3c_birds done
  elif result defeated
    setflag p3c_met_lugia
    say "Lugia sank beneath the water... It will surface again."
    hide lugia
    quest p3c_birds done
  end
end

# ================================================================ Legendary beasts
script p3c_raikou
  say "Thunder rolls across the meadow. A beast with a storm-cloud mane bares its fangs!"
  cry raikou
  wildbattle raikou 70 norun
  if result caught
    setflag p3c_caught_raikou
    hide raikou
  elif result defeated
    say "Raikou bounded away with a clap of thunder... It may return."
    hide raikou
  end
end

script p3c_entei
  say "The ash swirls. A volcanic beast roars, and the ground trembles!"
  cry entei
  wildbattle entei 70 norun
  if result caught
    setflag p3c_caught_entei
    hide entei
  elif result defeated
    say "Entei vanished in a burst of flame... It may return."
    hide entei
  end
end

script p3c_suicune
  say "A crystal-clear beast stands on the spring, as if walking on water. It looks straight at you."
  cry suicune
  wildbattle suicune 70 norun
  if result caught
    setflag p3c_caught_suicune
    hide suicune
  elif result defeated
    say "Suicune raced away across the waves... It may return."
    hide suicune
  end
end

# ================================================================ Sea, land and sky (Primal + Rayquaza)
script p3c_cinder_maiden
  faceplayer
  if flag p3c_met_rayquaza
    say "MAIDEN: Sea, land and sky are at peace. The isles thank you, Champion."
  elif flag p3c_met_kyogre and flag p3c_met_groudon
    say "MAIDEN: You've faced both titans! Then the doors of the Sky Pillar, east of Route 16, will open for you.\pRayquaza alone can quiet their ancient quarrel."
    if not flag p3c_maiden_gift
      setflag p3c_maiden_gift
      say "MAIDEN: Please, take this. My shrine has kept it for the one who would climb the pillar."
      tm earthpower
    end
  else
    say "MAIDEN: Groudon, titan of the land, slumbers in the Magma Vent below us. Its twin, Kyogre, lord of the sea, sleeps in the Seafloor Cavern on Route 16.\pEach is bound to an orb. Hold the orb, and the titan returns to its primal shape in battle."
    if not quest p3c_primal started
      quest p3c_primal start
    end
  end
end

script p3c_kyogre
  say "The lagoon churns. A colossal whale-like Pokémon surfaces, and rain begins to fall inside the cave!"
  cry kyogre
  wildbattle kyogre 72 norun
  if result caught
    setflag p3c_caught_kyogre
    setflag p3c_met_kyogre
    hide kyogre
    call p3c_blueorb
  elif result defeated
    setflag p3c_met_kyogre
    say "Kyogre sank into the depths... It will rise again."
    hide kyogre
  end
  call p3c_primal_check
end

script p3c_blueorb
  if not flag p3c_got_blueorb
    setflag p3c_got_blueorb
    say "Something glows where Kyogre floated... a deep blue orb!"
    giveitem blueorb
    say "When Kyogre holds the Blue Orb, it undergoes Primal Reversion at the start of battle!"
  end
end

script p3c_groudon
  say "The heat is unbearable... A titanic Pokémon heaves itself up from the magma!"
  cry groudon
  wildbattle groudon 72 norun
  if result caught
    setflag p3c_caught_groudon
    setflag p3c_met_groudon
    hide groudon
    call p3c_redorb
  elif result defeated
    setflag p3c_met_groudon
    say "Groudon sank back into the magma... It will rise again."
    hide groudon
  end
  call p3c_primal_check
end

script p3c_redorb
  if not flag p3c_got_redorb
    setflag p3c_got_redorb
    say "Something glows where Groudon slept... a blazing red orb!"
    giveitem redorb
    say "When Groudon holds the Red Orb, it undergoes Primal Reversion at the start of battle!"
  end
end

script p3c_primal_check
  if not quest p3c_primal started
    quest p3c_primal start
  end
  if flag p3c_met_kyogre and flag p3c_met_groudon and quest p3c_primal step < 1
    quest p3c_primal 1
    say "Far to the east, thunder rumbles above a lonely tower..."
  end
end

script p3c_pillar_gate
  if flag p3c_met_kyogre and flag p3c_met_groudon
    setflag p3c_pillar_open
    say "The great doors of the Sky Pillar rumble open!"
  else
    say "The doors of the Sky Pillar are sealed. Carvings show a whale and a beast locked in endless battle."
    move player down
  end
end

script p3c_pillar_monk
  faceplayer
  if flag p3c_caught_rayquaza
    say "MONK: Rayquaza travels with you... Teach it Dragon Ascent and, with a Key Stone, it can Mega Evolve without any stone at all. The one at the summit already knew it!"
  elif flag p3c_pillar_open
    say "MONK: The doors stand open. Climb, Champion. The sky waits at the top."
  else
    say "MONK: The Sky Pillar opens only when sea and land have both been faced. Find Kyogre and Groudon first."
  end
end

script p3c_rayquaza
  say "A roar splits the sky! An emerald serpent uncoils above the summit!"
  cry rayquaza
  wildbattle rayquaza 75 norun moves=dragonascent,extremespeed,outrage,dragondance
  if result caught
    setflag p3c_caught_rayquaza
    setflag p3c_met_rayquaza
    hide rayquaza
    quest p3c_primal done
  elif result defeated
    setflag p3c_met_rayquaza
    say "Rayquaza streaked off into the clouds... It will return to its pillar."
    hide rayquaza
    quest p3c_primal done
  end
end

# ================================================================ Sun, moon and Ultra Burst
script p3c_atoll_warden
  faceplayer
  if flag p3c_got_unz
    say "WARDEN: Sun and moon both answer to you now. Bring Necrozma to the Prism Pedestal in the Light Crater, and it may borrow their light."
  elif caught solgaleo or caught lunala
    say "WARDEN: The light of the altars follows you! The sun or moon guardian has chosen you.\pThen take this crystal. With it, the Prism Pokémon, clothed in sun or moon, can burst free in pure light."
    giveitem ultranecroziumz
    unlock ultraburst
    setflag p3c_got_unz
    if not quest p3c_ultra started
      quest p3c_ultra start
    end
    quest p3c_ultra done
    say "WARDEN: That is Ultranecrozium Z. Ultra Burst works through your Key Stone and needs no other gimmick.\pThe Prism Pedestal in the crater can fuse Necrozma with Solgaleo's or Lunala's light."
  else
    say "WARDEN: This atoll is a sundial and a moon-clock at once. The northern shrine holds the Altar of the Sun, the southern the Altar of the Moon.\pThe guardians of each await within. Earn the trust of either, and return to me."
    if not quest p3c_ultra started
      quest p3c_ultra start
    end
  end
end

script p3c_solgaleo
  say "Light pours through the altar's roof. A radiant lion steps out of the sunburst!"
  cry solgaleo
  wildbattle solgaleo 72 norun
  if result caught
    setflag p3c_caught_solgaleo
    hide solgaleo
    quest p3c_ultra 1
  elif result defeated
    say "Solgaleo dissolved into sunlight... It will descend again."
    hide solgaleo
  end
end

script p3c_lunala
  say "The altar goes dark as night. A great bat with crescent wings descends from the shadows!"
  cry lunala
  wildbattle lunala 72 norun
  if result caught
    setflag p3c_caught_lunala
    hide lunala
    quest p3c_ultra 1
  elif result defeated
    say "Lunala melted into moonlight... It will alight again."
    hide lunala
  end
end

script p3c_necrozma
  if caught necrozma
    setflag p3c_necrozma_gone
    hide necrozma
    return
  end
  say "Shards of light gather in the crater... The Prism Pokémon! It fled here to recover after Prism Summit!"
  cry necrozma
  wildbattle necrozma 72 norun
  if result caught
    setflag p3c_caught_necrozma
    setflag p3c_necrozma_gone
    hide necrozma
  elif result defeated
    say "Necrozma shattered into motes of light... They are already drifting back together. It will return."
    hide necrozma
  end
end

script p3c_prism_pedestal
  say "A pedestal of clear crystal. Faint light from both altars seems to meet here."
  if not flag p3c_got_unz
    say "Nothing happens. Perhaps the atoll's warden knows its purpose."
    return
  end
  choice "What will you do?" "Infuse with sunlight" "Infuse with moonlight" "Separate the light" "Leave it"
  if result 0
    if not caught solgaleo
      say "The pedestal stays dark. It needs the trust of the sun guardian, Solgaleo."
    else
      say "Choose a Necrozma to bathe in sunlight."
      trade necrozma necrozmaduskmane 75 item=ultranecroziumz
      if result 1
        say "Necrozma absorbed the sun's light and became Dusk Mane Necrozma!"
      else
        say "Nothing happened. Bring a Necrozma in your party."
      end
    end
  elif result 1
    if not caught lunala
      say "The pedestal stays dark. It needs the trust of the moon guardian, Lunala."
    else
      say "Choose a Necrozma to bathe in moonlight."
      trade necrozma necrozmadawnwings 75 item=ultranecroziumz
      if result 1
        say "Necrozma absorbed the moon's light and became Dawn Wings Necrozma!"
      else
        say "Nothing happened. Bring a Necrozma in your party."
      end
    end
  elif result 2
    choice "Which light?" "Dusk Mane" "Dawn Wings" "Cancel"
    if result 0
      trade necrozmaduskmane necrozma 75
      if result 1
        say "The sunlight drained away. Necrozma returned to its original form."
      else
        say "Nothing happened."
      end
    elif result 1
      trade necrozmadawnwings necrozma 75
      if result 1
        say "The moonlight drained away. Necrozma returned to its original form."
      else
        say "Nothing happened."
      end
    end
  end
end

# ================================================================ Mewtwo (Silent Grotto)
script p3c_grotto_ranger
  faceplayer
  say "RANGER: Halt. This grotto is sealed by order of the League. The Pokémon inside is too dangerous.\pOnly Champion Celeste can grant passage. Beat her in a rematch at the Champions' Hall, and I'll step aside."
end

script p3c_mewtwo
  say "A figure floats above the water, arms crossed. Its voice echoes inside your head:\p...Another human. Show me why I should not destroy you."
  cry mewtwo
  wildbattle mewtwo 75 norun
  if result caught
    setflag p3c_caught_mewtwo
    hide mewtwo
    quest p3c_isles done
  elif result defeated
    say "Mewtwo vanished in a ripple of psychic force... It will be back."
    hide mewtwo
    quest p3c_isles done
  end
end

# ================================================================ Power Spot Den (Eternatus, Max raids)
script p3c_den_researcher
  faceplayer
  say "RESEARCHER: I study Power Spots for Hollis. This den gives off more Dynamax energy than the whole League!\pSomething enormous sleeps at the core. You'll need a Dynamax Band just to stand near it.\pMy assistant inside also runs raid battles against Gigantamax Pokémon. Great training!"
end

script p3c_eternatus
  if not gimmick dynamax
    say "A crushing wave of Dynamax energy pushes you back! Without a Dynamax Band you can't get any closer."
    return
  end
  say "The den glows violet. A colossal, skeletal dragon unfurls, drinking in the Dynamax energy!"
  cry eternatus
  wildbattle eternatus 75 norun
  if result caught
    setflag p3c_caught_eternatus
    hide eternatus
    say "The violet glow faded to a gentle shimmer. The den feels calmer now."
  elif result defeated
    say "Eternatus dissolved into violet mist... The den is already pulling it back together."
    hide eternatus
  end
end

script p3c_raid_host
  faceplayer
  say "ASSISTANT: Max raid time! I lure a Gigantamax-factor Pokémon out of the energy pool, you battle it.\pCatch it and it's yours, Gigantamax factor and all!"
  if not quest p3c_raids started
    quest p3c_raids start
  end
  ask "Start a raid battle?"
  if result yes
    say "The pool flares red! A huge Pokémon emerges!"
    if random 8
      wildbattle charizard 65 gmax norun
    elif random 7
      wildbattle lapras 65 gmax norun
    elif random 6
      wildbattle snorlax 65 gmax norun
    elif random 5
      wildbattle gengar 65 gmax norun
    elif random 4
      wildbattle corviknight 65 gmax norun
    elif random 3
      wildbattle machamp 65 gmax norun
    elif random 2
      wildbattle duraludon 65 gmax norun
    else
      wildbattle hatterene 65 gmax norun
    end
    if result caught or result defeated
      call p3c_raid_reward
    else
      say "ASSISTANT: Ouch. The raid Pokémon was too much. Rest up and try again!"
    end
  else
    say "ASSISTANT: The pool isn't going anywhere!"
  end
end

script p3c_raid_reward
  addvar p3c_raid_wins 1
  say "ASSISTANT: Nice raid! Here's your share of the loot."
  if random 2
    giveitem dynamaxcandy
  else
    giveitem expcandyl
  end
  if var p3c_raid_wins >= 5 and not quest p3c_raids done
    say "ASSISTANT: Five raids cleared! Hollis said to give these to anyone who managed that."
    giveitem maxmushrooms
    giveitem wishingpiece 3
    quest p3c_raids done
  end
end
