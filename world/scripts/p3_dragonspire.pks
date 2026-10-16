# Dragonspire City, Gym 8 (Ryuga), Dragon Elder.

script ds_spire_guard
  faceplayer
  say "The Sky Spire is the dragons' sacred roost. Only a Champion may climb it, by order of the Elder."
end

script ds_hollis_news
  faceplayer
  say "HOLLIS: {player}! Big trouble. Team Eclipse stormed Victory Road and climbed to Prism Summit!\pThey've built a machine to drain the Prism Pokémon's light. If they succeed, Tessera falls into an endless eclipse."
  say "HOLLIS: The gate east on Route 13 leads into Victory Road. The way up to the summit splits off on 2F. Hurry!"
  setflag p3_heard_summit
end

script ds_gym_guide
  faceplayer
  if flag p3_beat_ryuga
    say "You tamed the dragon! The League is within reach."
  else
    say "Welcome to the last Gym! The bridges here move. The switch by the entrance swaps which bridge is out.\pTry the left platform first. And Ice moves, Ice moves, Ice moves!"
  end
end

script ds_gym_switch_a
  sfx switch
  if flag p3_ds_a
    clearflag p3_ds_a
    say "Clunk! The bridge swung back to the left platform."
  else
    setflag p3_ds_a
    say "Clunk! The bridge swung over to the right platform."
  end
end

script ds_gym_switch_b
  sfx switch
  if flag p3_ds_b
    clearflag p3_ds_b
    say "Clank! A distant bridge folded away."
  else
    setflag p3_ds_b
    say "Clank! Somewhere to the north, a bridge rumbled into place."
  end
end

script ds_ryuga
  faceplayer
  if flag hall_of_fame and flag p3_beat_ryuga
    ask "RYUGA: Champion. My dragons have not forgotten their defeat. Will you face them again?"
    if result yes
      battle p3_leader_ryuga_rematch
      if result win
        say "RYUGA: The dragon bows. As it should."
        if not flag p3_ryuga_rematch_prize
          giveitem garchompite
          setflag p3_ryuga_rematch_prize
        end
      end
    end
  elif flag p3_beat_ryuga
    say "RYUGA: You hold all eight badges. Victory Road awaits... but I hear Team Eclipse went there first. Go."
  else
    say "RYUGA: So, you crossed my dark tower. I am Ryuga, keeper of dragons.\pDragons answer only to strength. Show me yours, and do not hold back!"
    battle p3_leader_ryuga
    if result win
      say "RYUGA: ...Hmph. Magnificent. The Dusk Badge is yours."
      badge 8
      setflag p3_beat_ryuga
      tm dragonpulse
      say "RYUGA: Dragon Pulse. A dragon's roar made into a weapon."
      if flag unlock_zmove
        giveitem dragoniumz
      end
      quest p3_story next
    end
  end
end

script ds_elder
  faceplayer
  if flag p3_dragon_trial_done
    if flag hall_of_fame
      say "ELDER: Champion, the Sky Spire is open to you. The sky dragon has been restless."
    else
      say "ELDER: Raise that Dratini with love, and it will carry you over the clouds."
    end
  elif party has dragonite or party has garchomp or party has salamence or party has haxorus or party has hydreigon or party has goodra or party has kommoo or party has dragapult or party has baxcalibur or party has flygon
    say "ELDER: Oh! A fully grown dragon, bright-eyed and loyal. You have passed the Dragon's Trial."
    givemon dratini 30 moves=extremespeed,dragonrush,aquatail,dragondance
    if result 1
      setflag p3_dragon_trial_done
      quest p3_dragon_trial done
      say "ELDER: This Dratini knows Extreme Speed, a move from the old days. Treasure it."
    end
  else
    say "ELDER: Every dragon tamer here once faced the Dragon's Trial: raise a dragon to its final form.\pBring me such a Pokémon, and I will reward you."
    if not flag p3_dragon_trial_started
      setflag p3_dragon_trial_started
      quest p3_dragon_trial start
    end
  end
end

script ds_elder_aide
  faceplayer
  say "Fully grown dragons... Dragonite, Garchomp, Salamence, Haxorus, Hydreigon, Goodra, Kommo-o, Dragapult, Baxcalibur, Flygon. The Elder accepts any of them!"
end

script ds_move_reminder
  faceplayer
  say "I remember every move a Pokémon ever forgot. Want me to remind one of yours?"
  moverelearner
end
