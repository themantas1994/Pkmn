# Part 3B: Prism Plateau, League lobby, Elite Four and Champion Celeste.
# The lobby guard clears p3b_e4_1..4 and p3b_champion_beaten on every entry, so a loss (normal blackout to the
# last Pokémon Center) means facing the Elite Four again from the start. Champion victory (B5): Hall of Fame,
# credits (first time), flag hall_of_fame, warp to player_house_2f 3 4.

script p3b_plateau_ace
  faceplayer
  say "ACE TRAINER: The League hall is a power spot, just like the last two Gyms. The Elite Four will Mega Evolve, Dynamax, Terastallize... everything!\pThey each pick one trick, though. Figure out which, and you're halfway there."
end

script p3b_shop_league
  faceplayer
  say "Welcome! The League's own supply counter. Last stop before glory."
  shop p3b_league
end

script p3b_league_scientist
  faceplayer
  say "SCIENTIST: Morrow, Ghost. Ferrum, Steel. Sibyl, Psychic. Nox, Dark. And Champion Celeste uses a little of everything.\pI've studied every League battle for ten years. Never battled once! Too nervous."
end

script p3b_league_gate
  faceplayer
  say "GUARD: Beyond this door wait the Elite Four, and then the Champion.\pOnce you go in, there's no turning back until you win or your team falls."
  ask "GUARD: Are you ready to challenge the Pokémon League?"
  if result yes
    clearflag p3b_e4_1
    clearflag p3b_e4_2
    clearflag p3b_e4_3
    clearflag p3b_e4_4
    clearflag p3b_champion_beaten
    say "GUARD: Then go. All of Tessera is watching!"
    fadeout
    warp league_morrow 5 10 up
    fadein
  else
    say "GUARD: Take your time. Heal up, stock up, and come back when you're ready."
  end
end

# --- Elite Four ----------------------------------------------------------------
script p3b_e4_morrow
  face morrow down
  if flag p3b_e4_1
    say "MORROW: The door behind me is open. The spirits wish you well."
  else
    say "MORROW: Shh. Can you hear them? The spirits of every challenger who stood here...\pI am Morrow of the Elite Four. Let us add your story to theirs."
    battle p3b_elite_morrow
    if result win
      setflag p3b_e4_1
      say "MORROW: The way ahead is open. Ferrum is waiting. He is less... spooky."
    end
  end
end

script p3b_e4_ferrum
  face ferrum down
  if flag p3b_e4_2
    say "FERRUM: Go on. Your steel is true."
  else
    say "FERRUM: Ferrum of the Elite Four. Steel does not bend, does not break, does not fear.\pStrike me with everything you have. I will still be standing. Probably."
    battle p3b_elite_ferrum
    if result win
      setflag p3b_e4_2
      say "FERRUM: Hm. Bent, after all. The door is open."
    end
  end
end

script p3b_e4_sibyl
  face sibyl down
  if flag p3b_e4_3
    say "SIBYL: The future is unwritten after all. Go and write it."
  else
    say "SIBYL: I am Sibyl. This room sits on the heart of the power spot. Here, my Pokémon grow beyond imagining.\pI have foreseen this battle. Let us see whether my vision holds."
    battle p3b_elite_sibyl
    if result win
      setflag p3b_e4_3
      say "SIBYL: My vision was wrong. How delightful. Nox awaits you."
    end
  end
end

script p3b_e4_nox
  face nox down
  if flag p3b_e4_4
    say "NOX: The Champion's room is through there. Go and shine."
  else
    say "NOX: Nox, last of the Elite Four. I was a member of Team Eclipse once, long before Umbra.\pI learned that darkness only means something when there is light to stand beside it. Show me your light, {player}."
    battle p3b_elite_nox
    if result win
      setflag p3b_e4_4
      say "NOX: There it is. The light that broke the eclipse. The Champion is waiting for you."
    end
  end
end

# --- Champion ------------------------------------------------------------------
script p3b_champion
  face celeste down
  say "CELESTE: Welcome, {player}. I am Celeste, Champion of Tessera.\pI watched the summit light up from here. Every star in the sky seemed to turn toward Mt. Prism."
  say "CELESTE: I adapt to every challenger. Mega Evolution, Z-Moves, Terastal: I'll choose whatever your team calls for. Now, show me that light up close!"
  if random 3
    battle p3b_champion_celeste_mega
  elif random 2
    battle p3b_champion_celeste_zmove
  else
    battle p3b_champion_celeste_tera
  end
  if result win
    setflag p3b_champion_beaten
    say "CELESTE: Brilliant... Simply brilliant. You are the brightest star I have ever faced.\pFrom this moment on, you are Tessera's Champion!"
    show ch_linden
    move ch_linden up3
    say "PROF. LINDEN: {player}! I came as fast as I could. Champion! I always knew the bond between you and your Pokémon was something special."
    say "CELESTE: Come, both of you. There is a room beyond this one where Champions are remembered forever. Let us record your team in the Hall of Fame."
    quest p3b_story done
    heal
    fadeout
    if flag hall_of_fame
      halloffame
    else
      halloffame
      credits
    end
    setflag hall_of_fame
    warp player_house_2f 3 4 down
    fadein
  end
end

script p3b_champion_linden
  faceplayer
  say "PROF. LINDEN: Champion {player}! It has a nice ring to it, doesn't it?"
end
