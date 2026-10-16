# Expansion E2, leg 6 (Gym 5 -> Gym 6): Cinder Pass, Cinder Hollow, Ashen Trail, Ashfall's glass workshop.

# --- Route 9: Cinder Pass -------------------------------------------------------------
script p4b_r9_springgoer
  faceplayer
  say "OLD WOMAN: Fifty years I've soaked in this spring. My knees are the youngest part of me!\pThe cave up on the ridge feeds the spring its heat. Folks call it Cinder Hollow. Mind the Fire Breathers."
end

# --- Cinder Hollow: the hurt hiker ----------------------------------------------------
script p4b_hurt_hiker
  faceplayer
  if item burnheal
    say "HIKER: Ow, ow... A Slugma got me good. Is that a Burn Heal? You're a lifesaver!"
    takeitem burnheal
    setflag p4b_hiker_rescued
    if not quest p4b_ember_escort started
      quest p4b_ember_escort start
    end
    quest p4b_ember_escort done
    say "HIKER: Much better! Here, I found this on the way down. Better in your hands than mine!"
    tm flareblitz
    giveitem expcandyl
    fadeout
    hide p4b_hurt_hiker
    fadein
  else
    say "HIKER: Ow... A Slugma burned my leg and I can't climb the ladder like this.\pYou wouldn't have a Burn Heal, would you? Ashfall's mart sells them, if you don't."
    if not quest p4b_ember_escort started
      quest p4b_ember_escort start
    end
  end
end

# --- Ashen Trail: Trial by Fire -------------------------------------------------------
script p4b_apprentice_sora
  faceplayer
  if quest p4b_trial_by_fire done
    say "SORA: You beat the master?! I've been his apprentice for two years and he's never even let me spar!"
  elif trainer p4b_trail_breather_ash and trainer p4b_trail_breather_blaze and trainer p4b_trail_breather_coal
    say "SORA: All three students beaten! The master will see you now. His grotto is in the crater, north-west."
  else
    say "SORA: Welcome to the Ashen Trail, Master Kaen's training ground!\pBeat his three Fire Breather students, Brand, Blaze and Coal, and the master will battle you in the Obsidian Grotto."
    if not quest p4b_trial_by_fire started
      quest p4b_trial_by_fire start
    end
  end
end

script p4b_kaen
  faceplayer
  if quest p4b_trial_by_fire done
    say "KAEN: Fire is patience, then a single breath. Remember that against Blaise."
  elif trainer p4b_trail_breather_ash and trainer p4b_trail_breather_blaze and trainer p4b_trail_breather_coal
    say "KAEN: So. You walked through my three students like wind through ash.\pI taught Blaise of Ashfall to breathe fire. Now let's see what you breathe!"
    battle p4b_kaen_master canlose
    if result win
      if not quest p4b_trial_by_fire started
        quest p4b_trial_by_fire start
      end
      quest p4b_trial_by_fire done
      say "KAEN: Take my technique, and this. An egg from the crater's edge. It's warm, so it will hatch soon."
      tm heatwave
      giveegg larvesta 1
      say "KAEN: Heat Wave scorches every foe at once. And that egg holds a Larvesta. Raise it to be a sun."
    else
      say "KAEN: Your flame flickered. Rest, and come back hotter."
    end
  else
    say "KAEN: You've come far to find me, but not far enough.\pDefeat my three students on the Ashen Trail. Then we'll talk with our Pokémon."
    if not quest p4b_trial_by_fire started
      quest p4b_trial_by_fire start
    end
  end
end

# --- Ashfall City: the glass workshop ---------------------------------------------------
script p4b_vitra
  faceplayer
  if quest p4b_glass_furnace done
    say "VITRA: The furnace has never burned so even. Look at these vases! Not a single bubble."
  elif item heatrock
    say "VITRA: Is that a Heat Rock? The heart of a volcano in a stone! May I have it for my furnace?"
    ask "Give Vitra the Heat Rock?"
    if result yes
      takeitem heatrock
      if not quest p4b_glass_furnace started
        quest p4b_glass_furnace start
      end
      quest p4b_glass_furnace done
      say "VITRA: Wonderful! Here, I've been saving this for someone strong enough to handle it."
      giveitem lifeorb
      say "VITRA: A Life Orb. It boosts every attack, but it burns a little each time. Fire has a price."
    else
      say "VITRA: I understand. A Heat Rock is precious. My furnace will wait."
    end
  else
    say "VITRA: Welcome to my workshop! I blow glass from Ashfall's volcanic sand.\pMy furnace runs uneven, though. A Heat Rock would fix it. Cinder Hollow, east on Route 9, is full of hot stones."
    if not quest p4b_glass_furnace started
      quest p4b_glass_furnace start
    end
  end
end

script p4b_glass_kid
  faceplayer
  if flag p4b_traded_charcadet
    say "KID: Kindle's going to be a great Armarouge or Ceruledge someday! ...My Magcargo is so slow. I love it."
  else
    say "KID: My Charcadet keeps singeing Mom's glass! She says I need a calmer Pokémon."
    ask "Would you trade me a Magcargo for my Charcadet?"
    if result yes
      trade magcargo charcadet 40 nick=Kindle ot=VITRA
      if result 1
        setflag p4b_traded_charcadet
        say "KID: Thanks! Kindle, be good! ...Magcargo, you're SO warm. Perfect."
      else
        say "KID: No Magcargo? Slugma evolve into them. They crawl all over Cinder Hollow!"
      end
    else
      say "KID: Okay... Slugma live in Cinder Hollow, if you change your mind."
    end
  end
end
