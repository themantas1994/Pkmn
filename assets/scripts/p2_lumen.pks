# Part 2: Route 5 checkpoint, Lumen Village and the Lumen Ruins.

# --- Route 5 badge-2 gate (B1 contract) -----------------------------------
script p2_route5_gate
  if badges >= 2
    setflag p2_route5_open
    quest p2_story start
  else
    face r5_officer left
    say "OFFICER: Hold it! Route 5 is closed to trainers without the Tide Badge.\pBeat Marina at the Port Tidewater Gym, then come back."
    move player up
  end
end

script p2_route5_officer
  faceplayer
  if badges >= 2
    say "OFFICER: The Tide Badge! You're cleared.\pLumen Village is south. Voltara City has been having blackouts lately, so watch your step after dark."
  else
    say "OFFICER: Sorry, kid. Rules are rules. Only trainers with two badges may pass."
  end
end

script p2_route5_rodguy
  faceplayer
  if not flag p2_got_goodrod
    say "Fishing's all about patience. And a better rod.\pYou look like you'd appreciate a better rod. Here!"
    giveitem goodrod
    setflag p2_got_goodrod
    say "The Good Rod hooks Pokémon that ignore old rods. Try it anywhere there's water!"
  else
    say "Remoraid bite near the pier at dusk. Or is it dawn? Ah, they just bite."
  end
end

# --- Lumen Village ----------------------------------------------------------
script p2_lumen_elder
  faceplayer
  if flag p2_kanto_gift and gimmick mega and not flag p2_kanto_stone
    say "ELDER IONE: Ah! That glint on your wrist... a Key Stone!\pThen the traveler's last gift belongs with you too."
    if flag p2_kanto_bulbasaur
      giveitem venusaurite
    elif flag p2_kanto_charmander
      giveitem charizarditex
    else
      giveitem blastoisinite
    end
    setflag p2_kanto_stone
    quest p2_kanto_legacy done
    say "ELDER IONE: When your partner is fully grown, let it shine."
  elif flag p2_kanto_gift
    say "ELDER IONE: How is the little one from Kanto? Treat it as family.\pThe traveler also left a strange stone. It only reacts to people bearing a Key Stone..."
  elif flag p2_saw_mural
    say "ELDER IONE: You saw the mural with your own eyes? Then you understand.\pLong ago, a traveler from Kanto rested here. He left three Pokémon for someone who would respect the ruins."
    if not quest p2_kanto_legacy started
      quest p2_kanto_legacy start
      quest p2_kanto_legacy next
    end
    call p2_lumen_kanto_choice
  else
    say "ELDER IONE: Welcome to Lumen. Our ruins hold a mural of a Pokémon made of light itself.\pFew have seen it. The inner chamber has been sealed for years."
    if not quest p2_kanto_legacy started
      quest p2_kanto_legacy start
    end
  end
end

script p2_lumen_kanto_choice
  choice "Which Pokémon will you take?" "Bulbasaur" "Charmander" "Squirtle"
  if result 0
    givemon bulbasaur 18
    if result 1
      setflag p2_kanto_bulbasaur
      setflag p2_kanto_gift
    end
  elif result 1
    givemon charmander 18
    if result 1
      setflag p2_kanto_charmander
      setflag p2_kanto_gift
    end
  else
    givemon squirtle 18
    if result 1
      setflag p2_kanto_squirtle
      setflag p2_kanto_gift
    end
  end
  if flag p2_kanto_gift
    quest p2_kanto_legacy next
    say "ELDER IONE: Raise it well. It came a very long way to meet you."
  end
end

script p2_lumen_grandson
  faceplayer
  if flag p2_kanto_gift
    say "Grandpa gave away the traveler's Pokémon? Aww... I wanted one!\pJust kidding. It looks happier with you."
  else
    say "Grandpa says only someone who sees the mural deserves the traveler's gift.\pThe sealed door has two stone eyes. Maybe they need to be... stepped on?"
  end
end

script p2_lumen_researcher
  faceplayer
  if quest p2_ruins_research done
    say "RESEARCHER: My report is the talk of the academy! The ruins are a nest for Psychic power, no doubt about it."
  elif caught unown and caught natu and caught baltoy and caught sigilyph
    say "RESEARCHER: Unown, Natu, Baltoy AND Sigilyph? Your Pokédex is a goldmine!\pPlease, take this as thanks."
    giveitem twistedspoon
    giveitem rarecandy 2
    quest p2_ruins_research done
  else
    say "RESEARCHER: The ruins attract Psychic Pokémon. I need proof!\pCatch Unown, Natu, Baltoy and Sigilyph in the Lumen Ruins and show me your Pokédex."
    if not quest p2_ruins_research started
      quest p2_ruins_research start
    end
  end
end

script p2_lumen_weaver
  faceplayer
  if not flag p2_weaver_tm
    say "I weave tapestries of the mural. People in black coats bought ALL of them last week.\pThey wore a sun with a bite taken out of it... Team Eclipse, they said.\pHere, dear. My late husband's TM. Calm minds weave the best patterns."
    tm calmmind
    setflag p2_weaver_tm
  else
    say "Those Eclipse folks headed east toward Voltara. Nothing good comes from people who hate sunlight."
  end
end

# --- Lumen Ruins -------------------------------------------------------------
script p2_lumen_switch_a
  setflag p2_lumen_sw_a
  say "The stone eye under your feet began to glow!"
  call p2_lumen_check_door
end

script p2_lumen_switch_b
  setflag p2_lumen_sw_b
  say "The stone eye under your feet began to glow!"
  call p2_lumen_check_door
end

script p2_lumen_check_door
  if flag p2_lumen_sw_a and flag p2_lumen_sw_b and not flag p2_lumen_door
    setflag p2_lumen_door
    shake
    say "Somewhere in the ruins, stone ground against stone!"
  end
end

script p2_lumen_door_sign
  if flag p2_lumen_door
    say "The sealed door has opened. Cool air drifts up from below."
  else
    say "A sealed door. Two carved eyes stare out: one toward the morning sun, one toward the setting sun."
  end
end

script p2_lumen_mural
  say "A vast mural. A Pokémon of black crystal swallows a blazing sun...\pBelow it, a mountain shines in seven colors."
  if not flag p2_saw_mural
    setflag p2_saw_mural
    if quest p2_kanto_legacy started
      quest p2_kanto_legacy next
    end
    say "You committed the mural to memory. The Elder in Lumen Village would want to hear about this."
  end
end
