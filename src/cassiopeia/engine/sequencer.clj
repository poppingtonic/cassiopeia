(ns cassiopeia.engine.sequencer
  (:use overtone.live)
  (:require [cassiopeia.engine.mixers :as mixers]))

(defsynth phasor-skipping-sequencer
  "Supports looping and jumping position"
  [buf 0 rate-b 0 out-bus 0 start-point 0 bar-trg [0 :tr] loop? 0 amp 1.0 cb 0]
  (let [rate (in:kr rate-b 1)
        ph (phasor:ar :trig bar-trg
                      :rate (* rate (buf-rate-scale:kr buf))
                      :start 0
                      :end (buf-frames:kr buf)
                      :reset-pos start-point)
        br (buf-rd:ar 1 buf ph loop?)]
    (out:kr cb (a2k ph))
    (out out-bus (* amp br))))

(defsynth rater [out-bus 0 rate 1]  (out out-bus rate))

(defsynth mono-sequencer
  "Plays a single channel audio buffer."
  [buf 0 rate 1 out-bus 0 beat-num 0 pattern 0  num-steps 8 beat-cnt-bus 0 beat-trg-bus 0 rq-bus 0 pan 0 amp 0.7]
  (let [cnt      (in:kr beat-cnt-bus)
        beat-trg (in:kr beat-trg-bus)
        bar-trg  (and (buf-rd:kr 1 pattern cnt)
                      (= beat-num (mod cnt num-steps))
                      beat-trg)
        vol      (set-reset-ff bar-trg)]
    (out out-bus (* vol amp (scaled-play-buf 1 buf rate bar-trg)))))

(defn- start-synths [samples patterns mixers num-steps tgt-group beat-cnt-bus beat-trg-bus out-bus]
  (let [out-busses (if mixers
                     (map :in-bus mixers)
                     (repeat out-bus))]
    (doall (mapcat (fn [sample pattern out-bus]
                     (map (fn [step-idx]
                            (mono-sequencer [:tail tgt-group]
                                            :buf (to-sc-id sample)
                                            :beat-num step-idx
                                            :pattern (:pattern-buf pattern)
                                            :beat-cnt-bus beat-cnt-bus
                                            :beat-trg-bus beat-trg-bus
                                            :out-bus out-bus))
                          (range num-steps)))
                   samples
                   patterns
                   out-busses))))

(defn- mk-sequence-patterns
  "Setup our buffers"
  [samples num-steps]
  (doall (map (fn [sample]
                (with-meta {:num-steps num-steps
                            :pattern-buf (buffer num-steps)}
                  {:type ::sequence-pattern}))
              samples)))

(defn mk-sequencer [nk-group handle samples num-steps tgt-group beat-cnt-bus beat-trg-bus out-bus]
  (let [patterns (mk-sequence-patterns samples num-steps)
        container-group (group handle :tail tgt-group)
        seq-group       (group "lp-sequencer" :head container-group)
        mixer-group     (group "lp-mixers" :after seq-group)
        mixer-handles   (map #(str handle "-" %) (range (count samples)))
        mixers          (doall (map #(mixers/add-nk-mixer nk-group % mixer-group out-bus) mixer-handles))
        synths   (start-synths samples patterns mixers num-steps seq-group beat-cnt-bus beat-trg-bus out-bus)]
    (with-meta {:patterns patterns
                :num-steps num-steps
                :num-samples (count samples)
                :synths (agent synths)

                :beat-cnt-bus beat-cnt-bus
                :beat-trg-bus beat-trg-bus
                :out out-bus

                :seq-group seq-group
                :mixer-group mixer-group
                :mixer-handles mixer-handles
                :mixers mixers}
      {:type ::sequencer})))

(defn swap-samples! [sequencer samples]
  (send (:synths sequencer)
        (fn [synths]
          (kill (:seq-group sequencer))
          (start-synths (take (:num-samples sequencer) samples)
                        (:patterns sequencer)
                        (:mixers sequencer)
                        (:num-steps sequencer)
                        (:seq-group sequencer)
                        (:beat-cnt-bus sequencer)
                        (:beat-trg-bus sequencer)
                        (:out-bus sequencer)
                        ))))
