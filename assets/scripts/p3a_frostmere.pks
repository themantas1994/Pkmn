# Frostmere Town, Hollis (Dynamax Band), Gym 7 (Isolde), Route 12, Eternal Den.

script p3a_fm_gym_sealed
  say "The Gym door is frozen shut. A note is pinned to it:"
  say "\"Challengers must first visit Hollis at the lodge. The Gym is a Power Spot, and you'll want to be ready. -Isolde\""
  move player down
end

script p3a_fm_hollis
  faceplayer
  if not flag p3a_got_dynamaxband
    say "HOLLIS: Welcome to Frostmere! I'm Hollis, a ranger who studies Power Spots.\pThere's energy welling up under this town. On a Power Spot, Pokémon can Dynamax and grow gigantic for three turns!"
    say "HOLLIS: Isolde's Gym sits right on top of one. You'll need this to fight her on even ground."
    giveitem dynamaxband
    unlock dynamax
    setflag p3a_got_dynamaxband
    say "HOLLIS: Dynamax works only at Power Spots: the Frostmere and Dragonspire Gyms, the League... and a few dens. One Pokémon, once per battle. Go get 'em!"
    quest p3a_story next
  else
    say "HOLLIS: Dynamaxed moves become Max Moves. They change the weather and terrain, so plan ahead!"
  end
end

script p3a_fm_gym_guide
  faceplayer
  if flag p3a_beat_isolde
    say "Smooth skating, champ! Dragonspire is east, over Route 12."
  else
    say "Yo! The floor is solid ice. Once you start sliding, you won't stop until you bump into something.\pFire, Fighting, Rock and Steel moves will crack Isolde's defense. Watch out for her Dynamax!"
  end
end

script p3a_fm_isolde
  faceplayer
  if flag hall_of_fame and flag p3a_beat_isolde
    say "ISOLDE: Champion. The snow falls softer now that Tessera's skies are safe. Thank you."
  elif flag p3a_beat_isolde
    say "ISOLDE: Ryuga waits in Dragonspire. He is... intense. Do keep your scarf on."
  else
    say "ISOLDE: Welcome. I am Isolde. My Pokémon and I are the stillness of falling snow.\pBut snow becomes an avalanche. Let me show you, on a Power Spot."
    battle p3a_leader_isolde
    if result win
      say "ISOLDE: Ah... the ice melts. You've earned the Frost Badge."
      badge 7
      setflag p3a_beat_isolde
      tm blizzard
      say "ISOLDE: Blizzard never misses in a hailstorm. And with the Frost Badge, Waterfall can be used outside battle."
      if flag unlock_zmove
        giveitem iciumz
        say "ISOLDE: An Icium Z, for your Z-Ring."
      end
      quest p3a_story next
    end
  end
end

script p3a_fm_scarf_quest
  faceplayer
  if flag p3a_pip_reward
    say "Pip hasn't stopped talking about you! Thank you again, dear."
  elif flag p3a_pip_home
    say "Pip came home, cold and happy! Oh, thank you!\pPlease, take this. My late husband's Snom laid it before winter."
    giveegg snom 1
    if result yes
      giveitem expcandyl 2
      setflag p3a_pip_reward
      quest p3a_lost_pip done
    else
      say "Oh, your party and your boxes are full. Make some room and come back, dear. The egg can wait."
    end
  elif flag p3a_pip_quest
    say "Pip went to see the wild Snom on the frozen slope of Route 12. Please bring him home!"
  else
    say "Oh dear, oh dear. My grandson Pip ran off to Route 12 this morning to watch the Snom.\pThe frozen slope there is terribly slippery. Could you find him?"
    setflag p3a_pip_quest
    quest p3a_lost_pip start
  end
end

script p3a_r12_pip
  faceplayer
  if flag p3a_pip_quest
    say "PIP: Grandma sent you? Whoops... I slid all the way down here and couldn't climb back up!"
    say "PIP: I'll follow the edge home. Thanks!"
    setflag p3a_pip_home
    hide p3a_r12_pip
    quest p3a_lost_pip next
  else
    say "PIP: The Snom here are so fluffy! I could watch them all day."
  end
end

script p3a_fm_grandpa
  faceplayer
  if flag p3a_beat_isolde
    say "Isolde was my student once, you know. Now she teaches me! Ho ho."
  else
    say "Isolde skates like the wind. Her ace grows to a mountain when it Dynamaxes.\pAn old trick: Max Moves of Fire type bring out harsh sunlight. Ice hates that!"
  end
end

script p3a_fm_trade_girl
  faceplayer
  if flag p3a_traded_sneasel
    say "Holly the Delibird is so cuddly! How's Frosty doing?"
  else
    say "I've always wanted a Delibird! Mine's a Sneasel named Frosty, but she keeps stealing my mittens."
    ask "Would you trade me a Delibird for Frosty? She's holding something sharp she found!"
    if result yes
      trade delibird sneasel 44 nick=Frosty item=razorclaw
      if result 1
        setflag p3a_traded_sneasel
        say "Yay! Take good care of Frosty. Level her up at night with the Razor Claw and see what happens!"
      else
        say "Aww. Delibird live on Route 12, if you want to find one."
      end
    else
      say "Okay... Delibird live on Route 12, by the way. Hint, hint!"
    end
  end
end

script p3a_fm_lodge_scientist
  faceplayer
  if flag p3a_letter and not flag p3a_letter_done
    say "A letter? From my dad, in Ashfall? Let me see...\p\"Eat properly, wear a scarf, call your father.\" Oh, Dad."
    say "Thank you for carrying it all this way. Here, take these for your trouble."
    giveitem expcandyl 2
    giveitem maxrepel 2
    setflag p3a_letter_done
    quest p3a_letter done
  else
    say "Power Spots gather Galar particles from deep underground. On those spots, Pokémon can grow gigantic!"
  end
end
