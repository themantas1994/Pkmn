# Side-quest chain A: "The Linden Field Notes" (PKM-75, design PKM-51). One quest (p5a_linden_notes), seven steps.
# Volume N is quest step N-1 and needs N+1 badges. Each contact answers by step: earlier volume still open or badges
# missing (p5a_not_yet), the task, and already filed. Nothing here is on the critical path; rewards are items, no EXP.

# ---------------------------------------------------------------- Delivery (Hollowbrook lab)
# Called from lab_enter: the "mail" notice, once.
script p5a_mail
  if flag got_pokedex and badges >= 2 and not flag p5a_mail_seen
    setflag p5a_mail_seen
    say "A message is blinking on the lab terminal.\p\"Linden mailed you: Field Notes vol. 1 is ready. See my field aide.\""
  end
end

script p5a_lab_aide
  faceplayer
  if quest p5a_linden_notes done
    say "FIELD AIDE: The Professor filed your thesis under 'Remarkable'. He even smiled."
  elif quest p5a_linden_notes started
    say "FIELD AIDE: Your quest log lists where each volume goes. The Professor sends the next one with every new badge pair."
  elif badges >= 2
    say "FIELD AIDE: {player}! The Professor wants observations, not just species.\pHere is Field Notes vol. 1. His contact in Brindle City's Pokémon Center is waiting."
    quest p5a_linden_notes start
  else
    say "FIELD AIDE: The Professor has field notes for trainers who hold the Tide Badge.\pCome back after you beat the Port Tidewater Gym."
  end
end

# Shared answer for a contact whose volume is not open yet.
script p5a_not_yet
  if not quest p5a_linden_notes started
    say "RESEARCHER: I'm one of Prof. Linden's contacts. His field aide in the Hollowbrook lab has your notes."
  else
    say "RESEARCHER: The Professor hasn't sent me this volume yet. Finish your earlier volumes and keep earning badges."
  end
end

# ---------------------------------------------------------------- Vol. 1: bond observation (Brindle Pokémon Center)
script p5a_brindle_contact
  faceplayer
  if quest p5a_linden_notes done or quest p5a_linden_notes step > 0
    say "RESEARCHER: Your bond notes are filed. A Pokémon that trusts you is worth more than any item."
  elif quest p5a_linden_notes step == 0 and badges >= 2
    say "RESEARCHER: Prof. Linden's volume 1. Does your starter trust you? I only need to see how it stands beside you."
    if friendship chikorita >= 120 or friendship cyndaquil >= 120 or friendship totodile >= 120
      say "RESEARCHER: Look at that! It watches you instead of the crowd. That is a bond.\pHere, for the log. Three Ethers."
      giveitem ether 3
      quest p5a_linden_notes next
      say "RESEARCHER: Volume 2 is a night sighting near Voltara City. A trail camera is set up on Route 7."
    else
      say "RESEARCHER: Hm. Your starter keeps looking away. Bonds grow by walking together, winning battles and being healed at Centers.\pBring your starter in your party and come back when it has warmed up to you."
    end
  else
    call p5a_not_yet
  end
end

# ---------------------------------------------------------------- Vol. 2: time-of-day sighting (Route 7 camera, Voltara lab)
script p5a_r7_camera
  if quest p5a_linden_notes step == 1 and badges >= 3
    if flag p5a_photo
      say "The trail camera holds a clear night picture of a Joltik. Report to Mira's lab in Voltara City."
    elif not time night
      say "A trail camera on a tripod. The note says: \"Only works in the dark. Come back after sunset.\""
    elif seen joltik
      setflag p5a_photo
      say "Click! The trail camera catches a Joltik clinging to the pylon.\pThe picture is stored. Take it to Mira's lab in Voltara City."
    else
      say "The frame is empty. The note says: \"Joltik roam the grass here at night. Meet one first so the camera can lock on.\""
    end
  else
    say "A trail camera on a tripod labelled \"Linden Field Notes\"."
  end
end

script p5a_voltara_contact
  faceplayer
  if quest p5a_linden_notes done or quest p5a_linden_notes step > 1
    say "FIELD ASSISTANT: Mira loves the Joltik picture. It's on the lab wall now."
  elif quest p5a_linden_notes step == 1 and badges >= 3
    if flag p5a_photo
      say "FIELD ASSISTANT: A Joltik at night, with the pylon glowing behind it! The Professor will love it.\pFive Escape Ropes, from the lab's stores."
      giveitem escaperope 5
      quest p5a_linden_notes next
      say "FIELD ASSISTANT: Volume 3 is about fossils. Revive one in Quarrytown and show it in Lumen Village."
    else
      say "FIELD ASSISTANT: Volume 2: photograph a Pokémon seen only at night. The trail camera on Route 7 is set for Joltik.\pIt only works after dark."
    end
  else
    call p5a_not_yet
  end
end

# ---------------------------------------------------------------- Vol. 3: fossil lineage (Lumen Village)
script p5a_lumen_contact
  faceplayer
  if quest p5a_linden_notes done or quest p5a_linden_notes step > 2
    say "RESEARCHER: Fossils aren't dead. They are old, and patient."
  elif quest p5a_linden_notes step == 2 and badges >= 4
    if party has tyrunt or party has tyrantrum or party has amaura or party has aurorus or party has aerodactyl or party has cranidos or party has rampardos or party has shieldon or party has bastiodon or party has kabuto or party has kabutops or party has omanyte or party has omastar or party has anorith or party has armaldo or party has lileep or party has cradily
      say "RESEARCHER: A revived fossil Pokémon, alive and well! Look at its jaw, its skin. Millions of years, and it still fidgets.\pA hand-written note goes into the log. Three Ethers."
      giveitem ether 3
      quest p5a_linden_notes next
      say "RESEARCHER: Volume 4 is in Mistwood, north of Seraphine Town. Visit the fog logger in the morning, by day and at night."
    else
      say "RESEARCHER: Volume 3: fossil lineage. Find a fossil, have it revived at one of Quarrytown's labs, and bring the Pokémon here in your party."
    end
  else
    call p5a_not_yet
  end
end

# ---------------------------------------------------------------- Vol. 4: mist behaviour (Mistwood logger, Seraphine Fairy Keeper's house)
script p5a_mist_post
  if quest p5a_linden_notes step == 3 and badges >= 5
    if time morning and not flag p5a_mist_m
      setflag p5a_mist_m
      say "Morning reading: the fog is thick as milk. Wild Pokémon stay hidden in it."
    elif time day and not flag p5a_mist_d
      setflag p5a_mist_d
      say "Daytime reading: the fog thins to a haze. Fairy-type Pokémon come out to play in the clearings."
    elif time night and not flag p5a_mist_n
      setflag p5a_mist_n
      say "Night reading: the fog glows faintly. Tiny lights drift between the trees."
    elif flag p5a_mist_m and flag p5a_mist_d and flag p5a_mist_n
      say "All three readings are logged. Take them to the Fairy Keeper in Seraphine Town."
    else
      say "The logger records each time of day once: morning, day and night. This time of day is already logged. Come back at another."
    end
  else
    say "A weatherproof post labelled \"Linden Field Notes - fog logger\"."
  end
end

script p5a_seraphine_contact
  faceplayer
  if quest p5a_linden_notes done or quest p5a_linden_notes step > 3
    say "FAIRY KEEPER: The fog logs hang beside my kettle. I read them to the Clefairy at night."
  elif quest p5a_linden_notes step == 3 and badges >= 5
    if flag p5a_mist_m and flag p5a_mist_d and flag p5a_mist_n
      say "FAIRY KEEPER: Thick in the morning, thin by day, glowing at night. Three readings, three moods. The mist is alive!\pThe Professor sent a gift for you, and a little one from my own basket."
      giveegg eevee 1
      if result yes
        giveitem everstone
        quest p5a_linden_notes next
        say "FAIRY KEEPER: Eevee can become many things. The Everstone lets you decide when.\pVolume 5 is in Ashfall. Set the sensors by day."
      else
        say "FAIRY KEEPER: Oh dear, your party and boxes are full! Make room, then come back for the reward."
      end
    else
      say "FAIRY KEEPER: Volume 4: the Mistwood fog logger needs a reading in the morning, one by day and one at night.\pVisit the post in Mistwood at each time of day."
    end
  else
    call p5a_not_yet
  end
end

# ---------------------------------------------------------------- Vol. 5: thermal study (Ashfall City, Ashen Trail, Route 9, Magma Elder's house)
# Sensors are set by day (morning or day) and read after dark, so a full day passes between the two visits.
script p5a_sensor_city
  if quest p5a_linden_notes step == 4 and badges >= 6
    if flag p5a_sens_city_read
      say "Sensor reading: 31 degrees in the Ashfall City square, warmed by the Gym."
    elif flag p5a_sens_city
      if time night
        setflag p5a_sens_city_read
        say "The sensor has logged a full day: 31 degrees in the Ashfall City square, warmed by the Gym."
      else
        say "The sensor is still logging. It needs a full day: read it after dark."
      end
    elif time morning or time day
      setflag p5a_sens_city
      say "You set a Linden Field Notes sensor in the Ashfall City square. Read it after dark."
    else
      say "It is too dark to set the sensor. Come back in the morning or by day."
    end
  else
    say "A bracket on a post, labelled \"Linden Field Notes - sensor mount\"."
  end
end

script p5a_sensor_trail
  if quest p5a_linden_notes step == 4 and badges >= 6
    if flag p5a_sens_trail_read
      say "Sensor reading: 51 degrees on the Ashen Trail, the hottest of the three."
    elif flag p5a_sens_trail
      if time night
        setflag p5a_sens_trail_read
        say "The sensor has logged a full day: 51 degrees on the Ashen Trail, the hottest of the three."
      else
        say "The sensor is still logging. It needs a full day: read it after dark."
      end
    elif time morning or time day
      setflag p5a_sens_trail
      say "You set a Linden Field Notes sensor on the Ashen Trail. Read it after dark."
    else
      say "It is too dark to set the sensor. Come back in the morning or by day."
    end
  else
    say "A bracket on a post, labelled \"Linden Field Notes - sensor mount\"."
  end
end

script p5a_sensor_route9
  if quest p5a_linden_notes step == 4 and badges >= 6
    if flag p5a_sens_r9_read
      say "Sensor reading: 27 degrees on Route 9, the coolest of the three."
    elif flag p5a_sens_r9
      if time night
        setflag p5a_sens_r9_read
        say "The sensor has logged a full day: 27 degrees on Route 9, the coolest of the three."
      else
        say "The sensor is still logging. It needs a full day: read it after dark."
      end
    elif time morning or time day
      setflag p5a_sens_r9
      say "You set a Linden Field Notes sensor on Route 9. Read it after dark."
    else
      say "It is too dark to set the sensor. Come back in the morning or by day."
    end
  else
    say "A bracket on a post, labelled \"Linden Field Notes - sensor mount\"."
  end
end

script p5a_ashfall_contact
  faceplayer
  if quest p5a_linden_notes done or quest p5a_linden_notes step > 4
    say "RESEARCHER: Fifty-one degrees on the trail. Even the Slugma complained."
  elif quest p5a_linden_notes step == 4 and badges >= 6
    if flag p5a_sens_city_read and flag p5a_sens_trail_read and flag p5a_sens_r9_read
      say "RESEARCHER: 31, 51 and 27 degrees! The Ashen Trail is the hottest, as the Professor guessed.\pFive Escape Ropes, for the long trip north."
      giveitem escaperope 5
      quest p5a_linden_notes next
      say "RESEARCHER: Volume 6 is a cold-weather study. Walk Route 12 between Frostmere Town and Dragonspire City."
    else
      say "RESEARCHER: Volume 5: thermal study. Set a sensor by day in Ashfall City, on the Ashen Trail and on Route 9.\pRead each one after dark, then report to me."
    end
  else
    call p5a_not_yet
  end
end

# ---------------------------------------------------------------- Vol. 6: cold-weather walk (Route 12, Frostmere house)
script p5a_marker_1
  if quest p5a_linden_notes step == 5 and badges >= 7
    setflag p5a_cold_1
    say "Marker 1 (Frostmere end): wild Pokémon huddle in the lee of the rocks. Log saved."
  else
    say "A frost-proof marker labelled \"Linden Field Notes - route log\"."
  end
end

script p5a_marker_2
  if quest p5a_linden_notes step == 5 and badges >= 7
    setflag p5a_cold_2
    say "Marker 2 (mid-route): tracks lead to the frozen falls and stop. The snow keeps its secrets. Log saved."
  else
    say "A frost-proof marker labelled \"Linden Field Notes - route log\"."
  end
end

script p5a_marker_3
  if quest p5a_linden_notes step == 5 and badges >= 7
    setflag p5a_cold_3
    say "Marker 3 (Dragonspire end): the wind drops and the snow thins. Fewer Pokémon, but bigger ones. Log saved."
  else
    say "A frost-proof marker labelled \"Linden Field Notes - route log\"."
  end
end

script p5a_frostmere_contact
  faceplayer
  if quest p5a_linden_notes done or quest p5a_linden_notes step > 5
    say "RESEARCHER: Warm tea and a log full of snow. A good week."
  elif quest p5a_linden_notes step == 5 and badges >= 7
    if flag p5a_cold_1 and flag p5a_cold_2 and flag p5a_cold_3
      say "RESEARCHER: Three markers, three logs. Pokémon on Route 12 hide in the cold and come out in the warm.\pHere, three Ethers for the long road."
      giveitem ether 3
      quest p5a_linden_notes next
      say "RESEARCHER: The last volume is your thesis. Present it at the Collector's House in Dragonspire City."
    else
      say "RESEARCHER: Volume 6: log all three markers along Route 12, the road between here and Dragonspire City."
    end
  else
    call p5a_not_yet
  end
end

# ---------------------------------------------------------------- Vol. 7: final thesis (Dragonspire Collector's House)
# p5a_q counts questions answered correctly. A wrong answer ends the talk; the next talk resumes at that question.
script p5a_thesis_contact
  faceplayer
  if quest p5a_linden_notes done
    say "DR. HOLT: Your thesis hangs in the Professor's lab. He asks after you."
  elif quest p5a_linden_notes step == 6 and badges >= 8
    if var p5a_q == 0
      say "DR. HOLT: Volume 7: the thesis. Five questions about what you observed. Take your time."
    end
    if var p5a_q < 5
      call p5a_thesis_ask
    end
    if var p5a_q >= 5
      call p5a_thesis_finish
    end
  else
    call p5a_not_yet
  end
end

script p5a_thesis_ask
  if var p5a_q == 0
    choice "Q1: What did Volume 1 measure?" "Strength" "Friendship" "Speed"
    if result 1
      addvar p5a_q 1
      say "DR. HOLT: Yes. Trust, not power."
    else
      say "DR. HOLT: Check your first volume. It was about how a Pokémon stands beside you."
      return
    end
  end
  if var p5a_q == 1
    choice "Q2: Which Pokémon did you photograph near Voltara?" "Joltik" "Golbat" "Sandshrew"
    if result 0
      addvar p5a_q 1
      say "DR. HOLT: Joltik, at night on the pylon."
    else
      say "DR. HOLT: Check your second volume. Something small, in the dark, with a spark."
      return
    end
  end
  if var p5a_q == 2
    choice "Q3: Where are fossils revived?" "Quarrytown" "Hollowbrook" "Port Tidewater"
    if result 0
      addvar p5a_q 1
      say "DR. HOLT: In the fossil labs of Quarrytown."
    else
      say "DR. HOLT: Check your third volume. A town of stone."
      return
    end
  end
  if var p5a_q == 3
    choice "Q4: When was Mistwood's fog thinnest?" "Morning" "Day" "Night"
    if result 1
      addvar p5a_q 1
      say "DR. HOLT: By day, a haze, and the fairies come out."
    else
      say "DR. HOLT: Check your fourth volume. The fairies only play when they can see each other."
      return
    end
  end
  if var p5a_q == 4
    choice "Q5: Which Ashfall zone read hottest?" "Ashfall City" "Ashen Trail" "Route 9"
    if result 1
      addvar p5a_q 1
      say "DR. HOLT: Fifty-one degrees on the Ashen Trail."
    else
      say "DR. HOLT: Check your fifth volume. Where did the Slugma complain?"
      return
    end
  end
end

script p5a_thesis_finish
  say "DR. HOLT: That is a thesis!\pBut it is not over. A rival researcher disputes the Professor's whole field."
  say "DR. ORREL: Bonds, sightings, fossils... anecdotes! Show me data, or show me a battle!"
  battle p5a_orrel canlose
  if result win
    say "DR. ORREL: ...Fine. The data holds. I withdraw my objection."
    giveitem abilitycapsule
    setflag p5a_researchers_pin
    quest p5a_linden_notes done
    say "DR. HOLT: The Professor sent this: an Ability Capsule, and his pin of thanks.\pYou have earned a place in the Linden Field Notes. He'd like a word at the lab."
  else
    say "DR. ORREL: Your thesis needs a stronger defence. Come back when you are ready."
  end
end

# ---------------------------------------------------------------- Post-game (lab)
script p5a_professor_thanks
  say "PROF. LINDEN: Your field notes changed how I teach. Observation first, theory second.\pThe pin you earned belongs to the Linden Field Notes. Wear it with pride, {player}."
end
