# Part 3C: Coral Town NPCs, Pokémon Center, Mart, houses, rival rematch and side quests.

script p3c_nurse
  faceplayer
  say "Aloha! Welcome to the Coral Isles Pokémon Center!"
  ask "Shall we restore your Pokémon to full health?"
  if result yes
    say "OK, may I see your Pokémon?"
    healcenter
    say "Your Pokémon are fighting fit! Enjoy the islands!"
  else
    say "We hope to see you again!"
  end
end

script p3c_coral_clerk
  faceplayer
  say "Aloha! Welcome to the Coral Mart!"
  shop p3c_coral_mart
  say "Mahalo! Come again!"
end

script p3c_coral_clerk2
  faceplayer
  say "This counter stocks technical machines for Champions. Prices are... Champion-sized."
  shop p3c_coral_tms
  say "Mahalo!"
end

script p3c_bp_info
  faceplayer
  say "Battle Points, BP for short, are earned only at the Battle Tower.\pOne BP per win, plus a bonus for clearing all seven rounds. Beat the Tycoon for a fortune!\pSpend them at the Tower's exchange counter. Mints, Ability Capsules, rare Mega Stones..."
end

# ---------------------------------------------------------------- Coral Town
script p3c_coral_fisher
  faceplayer
  if item superrod
    say "FISHERMAN: Kingdra under the cold current, Milotic on the reefs... the isles are a fisher's paradise."
  else
    say "FISHERMAN: A Champion without a Super Rod? Unthinkable! Here, take my spare."
    giveitem superrod
    say "FISHERMAN: The deeper the water, the bigger the bite!"
  end
end

script p3c_coral_legends
  faceplayer
  if quest p3c_legends done
    say "OLD MAN: Sun, moon, sea, land, sky and storm... all six guardians trust you. I can die a happy old man. Not today, though!"
  elif caught solgaleo and caught lunala and caught lugia and caught kyogre and caught groudon and caught rayquaza
    say "OLD MAN: You... you've done it! All six guardians of the isles stand with you!\pMy grandfather's grandfather wrote of this. Take this, Champion. It's been in my family for generations."
    giveitem masterball
    giveitem goldbottlecap
    quest p3c_legends done
  else
    say "OLD MAN: Six guardians watch over these isles, young Champion.\pThe sun lion and moon bat on Eclipse Atoll. The storm bird beneath Tempest Isle.\pThe sea and land titans, south and west. And the sky serpent atop the Sky Pillar, who settles their quarrels.\pCatch all six and come tell me. I'd love to see it before I go."
    if not quest p3c_legends started
      quest p3c_legends start
    end
  end
end

script p3c_rival_coral
  faceplayer
  if not flag p3c_rival_met
    say "{rival}: {player}! Took you long enough. I came on the first ferry after your Hall of Fame.\pThe Champion's Hall, the Battle Tower, legends everywhere... This place is amazing!\pBut first things first. Champion or not, you still owe me a battle!"
  else
    say "{rival}: Back for another round? I've been training on Route 14 all week!"
  end
  ask "Battle {rival}?"
  if result yes
    if flag starter_grass
      battle p3c_rival_typhlosion canlose
    elif flag starter_fire
      battle p3c_rival_feraligatr canlose
    else
      battle p3c_rival_meganium canlose
    end
    if result win
      if not flag p3c_rival_met
        say "{rival}: Argh! Still the best. Fine! Take this. Professor Linden asked me to give it to you."
        giveitem expcandyxl 3
        giveitem abilitycapsule
      else
        say "{rival}: One day, {player}. One day!"
      end
    else
      say "{rival}: YES! I finally did it! ...Okay, don't tell anyone I cried."
    end
    setflag p3c_rival_met
    heal
    say "{rival}: There. Your team's patched up. Let's both get stronger!"
  else
    setflag p3c_rival_met
    say "{rival}: Chicken! ...Kidding. Come find me when you're ready."
  end
end

# ---------------------------------------------------------------- Nalu's House (Alolan starter gift)
script p3c_nalu
  faceplayer
  if flag p3c_nalu_gift
    say "NALU: How is the little one doing? Treat it like family, and it will treat you the same."
  else
    say "NALU: Aloha, Champion. I raise Pokémon from far-off Alola. Three little ones are ready for a trainer.\pWill you take one?"
    choice "Which Pokémon?" "Rowlet" "Litten" "Popplio" "Not now"
    if result 0
      givemon rowlet 50
      call p3c_nalu_done
    elif result 1
      givemon litten 50
      call p3c_nalu_done
    elif result 2
      givemon popplio 50
      call p3c_nalu_done
    else
      say "NALU: Take your time, dear. They're not going anywhere."
    end
  end
end

script p3c_nalu_done
  # givemon's result is overwritten by its message box, so check the Pokédex/party instead.
  if hasmon rowlet or hasmon litten or hasmon popplio
    setflag p3c_nalu_gift
    say "NALU: It's already looking at you like you hung the moon. Take good care of it!"
  end
end

script p3c_shell_kid
  faceplayer
  if quest p3c_shells done
    say "My shell collection is the best in the isles, thanks to you!"
  elif item bigpearl >= 3
    say "Whoa! Three Big Pearls?! Can I have them? Please please please?"
    ask "Give three Big Pearls?"
    if result yes
      takeitem bigpearl 3
      say "YES! Here, take this. I found it in a shipwreck. I think it's important?"
      giveitem abilitypatch
      quest p3c_shells done
    else
      say "Aww..."
    end
  else
    say "I collect shiny things from the beach! Big Pearls are the best.\pPeople say they're hidden on beaches all over the isles. If you find three, bring them to me!"
    if not quest p3c_shells started
      quest p3c_shells start
    end
  end
end

# ---------------------------------------------------------------- Islander's House (trade, lore)
script p3c_trader
  faceplayer
  if flag p3c_traded
    say "TRADER: My Exeggutor loves your old Exeggutor... I mean, I love it! Thank you!"
  else
    say "TRADER: I'm studying how island sun changes Pokémon. I'd love a regular Exeggutor from the mainland!\pI'll trade you my Alolan Exeggutor. It's a tall one!"
    ask "Trade an Exeggutor for Alolan Exeggutor?"
    if result yes
      trade exeggutor exeggutoralola 60 nick=Palmtree
      if result 1
        setflag p3c_traded
        quest p3c_trade done
        say "TRADER: Wonderful! Look how short it is next to mine. Ha!"
      else
        say "TRADER: Hmm, I need a mainland Exeggutor in your party. Exeggcute evolve with a Leaf Stone!"
        if not quest p3c_trade started
          quest p3c_trade start
        end
      end
    else
      say "TRADER: If you change your mind, I'll be here."
      if not quest p3c_trade started
        quest p3c_trade start
      end
    end
  end
end

script p3c_scholar
  faceplayer
  say "SCHOLAR: Before Team Eclipse, before the League, islanders believed Mt. Prism's light came from the sky.\pThey built twin altars on the atoll: one for the sun, one for the moon. When both shone together, the Prism Pokémon could take a shape of pure light.\pI'd give anything to see that... an Ultra Burst."
end
