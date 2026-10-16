# Frostmere Town, Hollis (Dynamax Band), Gym 7 (Isolde), Route 12, Eternal Den.

script fm_gym_sealed
  say "The Gym door is frozen shut. A note is pinned to it:"
  say "\"Challengers must first visit Hollis at the lodge. The Gym is a Power Spot, and you'll want to be ready. -Isolde\""
  move player down
end

script fm_hollis
  faceplayer
  if not flag p3_got_dynamaxband
    say "HOLLIS: Welcome to Frostmere! I'm Hollis, a ranger who studies Power Spots.\pThere's energy welling up under this town. On a Power Spot, Pokémon can Dynamax and grow gigantic for three turns!"
    say "HOLLIS: Isolde's Gym sits right on top of one. You'll need this to fight her on even ground."
    giveitem dynamaxband
    unlock dynamax
    setflag p3_got_dynamaxband
    say "HOLLIS: Dynamax works only at Power Spots: the Frostmere and Dragonspire Gyms, the League... and a few dens. One Pokémon, once per battle. Go get 'em!"
    quest p3_story next
  elif flag hall_of_fame
    say "HOLLIS: Champion! I've detected a monstrous energy reading inside the mountain on Route 12. A den has cracked open.\pI'm heading there now. Meet me if you dare!"
  else
    say "HOLLIS: Dynamaxed moves become Max Moves. They change the weather and terrain, so plan ahead!"
  end
end

script fm_gym_guide
  faceplayer
  if flag p3_beat_isolde
    say "Smooth skating, champ! Dragonspire is east, over Route 12."
  else
    say "Yo! The floor is solid ice. Once you start sliding, you won't stop until you bump into something.\pFire, Fighting, Rock and Steel moves will crack Isolde's defense. Watch out for her Dynamax!"
  end
end

script fm_isolde
  faceplayer
  if flag hall_of_fame and flag p3_beat_isolde
    ask "ISOLDE: The Champion graces my rink. Shall we dance once more?"
    if result yes
      battle p3_leader_isolde_rematch
      if result win
        say "ISOLDE: Exquisite. Winter bows to you."
        if not flag p3_isolde_rematch_prize
          giveitem abomasite
          setflag p3_isolde_rematch_prize
        end
      end
    end
  elif flag p3_beat_isolde
    say "ISOLDE: Ryuga waits in Dragonspire. He is... intense. Do keep your scarf on."
  else
    say "ISOLDE: Welcome. I am Isolde. My Pokémon and I are the stillness of falling snow.\pBut snow becomes an avalanche. Let me show you, on a Power Spot."
    battle p3_leader_isolde
    if result win
      say "ISOLDE: Ah... the ice melts. You've earned the Frost Badge."
      badge 7
      setflag p3_beat_isolde
      tm blizzard
      say "ISOLDE: Blizzard never misses in a hailstorm. And with the Frost Badge, Waterfall can be used outside battle."
      if flag unlock_zmove
        giveitem iciumz
        say "ISOLDE: An Icium Z, for your Z-Ring."
      end
      quest p3_story next
    end
  end
end

script fm_scarf_quest
  faceplayer
  if flag p3_pip_reward
    say "Pip hasn't stopped talking about you! Thank you again, dear."
  elif flag p3_pip_home
    say "Pip came home, cold and happy! Oh, thank you!\pPlease, take this. My late husband's Snom laid it before winter."
    giveegg snom 1
    giveitem expcandyl 2
    setflag p3_pip_reward
    quest p3_lost_pip done
  elif flag p3_pip_quest
    say "Pip went to see the wild Snom on the frozen slope of Route 12. Please bring him home!"
  else
    say "Oh dear, oh dear. My grandson Pip ran off to Route 12 this morning to watch the Snom.\pThe frozen slope there is terribly slippery. Could you find him?"
    setflag p3_pip_quest
    quest p3_lost_pip start
  end
end

script r12_pip
  faceplayer
  if flag p3_pip_quest
    say "PIP: Grandma sent you? Whoops... I slid all the way down here and couldn't climb back up!"
    say "PIP: I'll follow the edge home. Thanks!"
    setflag p3_pip_home
    hide r12_pip
    quest p3_lost_pip next
  else
    say "PIP: The Snom here are so fluffy! I could watch them all day."
  end
end

script fm_grandpa
  faceplayer
  if flag p3_beat_isolde
    say "Isolde was my student once, you know. Now she teaches me! Ho ho."
  else
    say "Isolde skates like the wind. Her ace grows to a mountain when it Dynamaxes.\pAn old trick: Max Moves of Fire type bring out harsh sunlight. Ice hates that!"
  end
end

script fm_trade_girl
  faceplayer
  if flag p3_froslass_gift
    say "Froslass is so elegant! I want one when I grow up."
  elif party has froslass
    say "Is that a Froslass? It's beautiful! I've been saving this for someone who raised one."
    giveitem dawnstone
    setflag p3_froslass_gift
  else
    say "Did you know a female Snorunt evolves into Froslass with a Dawn Stone? Show me one someday!"
  end
end

script den_hollis
  faceplayer
  if flag p3_eternatus_done
    say "HOLLIS: The readings are normal now. You tamed a living Power Spot! Unbelievable."
  else
    say "HOLLIS: This den is the source of every Power Spot in Tessera. Something enormous is feeding on the energy.\pBe careful, Champion. It can Dynamax by itself!"
  end
end
