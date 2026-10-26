# Part 2: Mt. Prism (admin Corvin, rival L37) and Seraphine Town (Gym 5, Elowen).

script p2_prism_pilgrim
  faceplayer
  say "PILGRIM: You look weary, traveler. The crystals here sing a soothing song. Rest a moment."
  heal
  say "PILGRIM: Ahead, people in black are cutting into the mountain's heart. Please, stop them."
end

script p2_corvin
  face corvin down
  say "CORVIN: Well, well. The brat who ruined our siphon in Voltara.\pI am Corvin, Admin of Team Eclipse. This mountain's light will feed the eclipse, and you will not interfere."
  battle p2_admin_corvin
  say "CORVIN: Tch. Your light is irritatingly bright.\pNo matter. We've harvested enough crystal here. The summit can wait for Lord Umbra."
  setflag p2_beat_corvin
  quest p2_story next
  fadeout
  hide corvin
  fadein
end

script p2_prism_rival
  emote mp3_rival !
  face mp3_rival left
  say "{rival}: {player}! I chased an Eclipse grunt all the way up here. Did you see that creepy admin guy run off?\pThey keep talking about the summit... Anyway! You're here, I'm here. You know what that means!"
  if flag starter_grass
    battle p2_rival_prism_typhlosion canlose
  elif flag starter_fire
    battle p2_rival_prism_feraligatr canlose
  else
    battle p2_rival_prism_meganium canlose
  end
  if result win
    say "{rival}: Unbelievable... You're the real deal, {player}. I'm gonna catch up. Just watch."
  else
    say "{rival}: Yes! Finally! ...don't tell anyone how close that was."
  end
  heal
  say "{rival}: Seraphine Town is right outside. Their Gym Leader uses Fairy-types, so my Dragon plans are on hold. See ya!"
  setflag p2_rival_prism_done
  fadeout
  hide mp3_rival
  fadein
end

script p2_prism_rival_talk
  faceplayer
  say "{rival}: Hey! Over here!"
end

# --- Seraphine Town -------------------------------------------------------------
script p2_seraphine_oldman
  faceplayer
  if badge 5
    say "With the Mist Badge you can Fly! Visit any town you've been to in a flash."
  else
    say "The mist here never lifts. Elowen says it hides the shy Fairy Pokémon from the harsh sun.\pTeam Eclipse would love that, I suppose. Hmph."
  end
end

script p2_seraphine_bard
  faceplayer
  if quest p2_mist_song done
    say "BARD: \"Oh Swablu, cloud of cotton, wing of song...\" The ballad is a hit!"
  elif party has swablu
    say "BARD: A Swablu! Look at those cotton wings! My muse has returned!\pPlease, take this treasure. It was the inspiration for my last verse."
    giveitem altarianite
    quest p2_mist_song done
  else
    say "BARD: I'm writing a ballad about Swablu, but I've never seen one up close!\pThey flutter in the Mist Grove. Could you bring one to show me?"
    if not quest p2_mist_song started
      quest p2_mist_song start
    end
  end
end

script p2_seraphine_tutor
  faceplayer
  say "My brother writes songs. I teach moves! Fairy moves, mostly. Want to learn?"
  movetutor seraphine_tutor
end

script p2_seraphine_keeper
  faceplayer
  if not flag p2_togepi_egg
    say "I look after the Fairy Pokémon of the grove. This Egg was left on my doorstep.\pIt needs someone with a kind heart. Will you raise it?"
    giveegg togepi 1
    if result 1
      setflag p2_togepi_egg
      say "Togepi is said to bring happiness. It will grow with love!"
    end
  else
    say "Togepi evolves when it trusts you completely. A Soothe Bell can help."
  end
end

# --- Gym 5 ---------------------------------------------------------------------
script p2_sgym_guide
  faceplayer
  if badge 5
    say "You saw through the mist! Amazing!"
  else
    say "Yo! Elowen's mist walls dissolve when you find the right switches.\pBut some switches are tricks that send you back to the start! Fairies love a good prank.\pPoison and Steel moves work wonders here."
  end
end

script p2_sgym_sw_a
  setflag p2_sg_a
  say "A soft chime! The mist on the left melted away."
end

script p2_sgym_sw_b
  setflag p2_sg_b
  say "A soft chime! The mist on the right melted away."
end

script p2_sgym_sw_c
  setflag p2_sg_c
  say "A soft chime! The mist before Elowen melted away."
end

script p2_sgym_trap
  say "Giggles echo through the mist... The floor glows, and the world spins!"
  warp seraphine_gym 7 16 up
end

script p2_elowen
  faceplayer
  if badge 5
    say "ELOWEN: May the mist guide you, and may your light never dim."
  else
    say "ELOWEN: Welcome, traveler from beyond the mountain. I am Elowen.\pThe mist protects what is gentle. Show me that your strength is gentle, too."
    battle p2_leader_elowen
    if result win
      say "ELOWEN: Your bond shines through even the thickest mist. The Mist Badge is yours."
      badge 5
      setflag p2_badge5
      say "ELOWEN: With it, you may Fly to any town you have visited."
      tm dazzlinggleam
      giveitem fairiumz
      say "ELOWEN: Dazzling Gleam, and a Fairium Z. With your Z-Ring, it becomes a Twinkle Tackle."
      quest p2_story done
    end
  end
end
