import SwiftUI

/// Overlay native saat lokasi simulasi terdeteksi (setara FakeGpsBlockScreen Android).
struct FakeGpsBlockView: View {
    let onRecheck: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 14) {
                ZStack {
                    Circle()
                        .fill(Color.white.opacity(0.2))
                        .frame(width: 72, height: 72)
                    Text("!")
                        .font(.system(size: 36, weight: .bold))
                        .foregroundColor(.white)
                }
                Text("Fake GPS terdeteksi")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                Text("Perangkat Anda tercatat untuk verifikasi.")
                    .font(.system(size: 13))
                    .foregroundColor(.white.opacity(0.92))
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 48)
            .padding(.bottom, 28)
            .padding(.horizontal, 24)
            .background(Color(red: 1, green: 0.478, blue: 0.227))

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Aplikasi terkunci")
                        .font(.system(size: 16, weight: .semibold))
                    Text("Matikan simulasi lokasi / Fake GPS di perangkat, lalu tekan tombol di bawah untuk lanjut absensi.")
                        .font(.system(size: 14))
                        .foregroundColor(Color(red: 0.12, green: 0.16, blue: 0.22))
                    Text("Perangkat Anda telah dicatat oleh sistem dan akan dilanjutkan ke proses verifikasi oleh tim yang berwenang.")
                        .font(.system(size: 13))
                        .foregroundColor(Color(red: 0.57, green: 0.25, blue: 0.05))
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(red: 1, green: 0.97, blue: 0.93))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color(red: 0.99, green: 0.91, blue: 0.54), lineWidth: 1)
                        )
                        .cornerRadius(12)
                    Group {
                        Text("• Nonaktifkan Fake GPS / Location Simulation")
                        Text("• Tutup aplikasi Fake GPS bila ada")
                        Text("• Pastikan GPS / lokasi aktif")
                    }
                    .font(.system(size: 13))
                    .foregroundColor(Color(red: 0.42, green: 0.45, blue: 0.50))
                }
                .padding(24)
            }

            Button(action: onRecheck) {
                Text("Periksa ulang")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(Color(red: 1, green: 0.478, blue: 0.227))
                    .cornerRadius(12)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 36)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.white)
    }
}
