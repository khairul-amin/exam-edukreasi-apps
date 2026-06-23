let countdownInterval;
let dataWatchdog;

function startDataWatchdog() {
    // Bersihkan timer lama sebelum memulai yang baru
    cancelWatchdog();
    
    dataWatchdog = setTimeout(() => {
        const examGrid = document.getElementById('examGrid');
        const infoBox = document.getElementById('infoBox');
        
        // JANGAN tampilkan "Koneksi Lambat" jika infoBox sudah tampil (sedang ada pesan lain)
        if (infoBox.style.display === 'flex') {
            return; 
        }

        if (examGrid.innerHTML === "") {
            showInfoMessage(
                "Koneksi Lambat", 
                "Gagal memuat daftar ujian. Pastikan internet stabil dan silakan klik tombol <b>Reload</b> di pojok kanan atas."
            );
        }
    }, 20000); 
}

function cancelWatchdog() {
    if (dataWatchdog) {
        clearTimeout(dataWatchdog);
        dataWatchdog = null;
    }
}

function initializeUI(user, exams) {
    cancelWatchdog();

    document.getElementById('userName').textContent = user.name;
    document.getElementById('userEmail').textContent = user.email;
    document.getElementById('userAvatar').textContent = user.name.charAt(0).toUpperCase();

    const examGrid = document.getElementById('examGrid');
    const noExams = document.getElementById('noExams');

    if (!exams || exams.length === 0) {
        examGrid.style.display = 'none';
        noExams.style.display = 'block';
        return;
    }

    noExams.style.display = 'none';
    examGrid.style.display = 'grid';
    examGrid.innerHTML = '';
    exams.forEach((exam, index) => {
        examGrid.appendChild(createExamCard(exam, index + 1));
    });

    if (countdownInterval) clearInterval(countdownInterval);
    updateCountdowns();
    countdownInterval = setInterval(updateCountdowns, 1000);
}

function createExamCard(exam, number) {
    const card = document.createElement('div');
    card.className = 'exam-card';

    const displayClass = exam.kelas || '-';
    const startDate = safeFormatDate(exam.tanggalMulai);
    const startTime = exam.jamMulai || '00:00';
    const endDate = safeFormatDate(exam.tanggalSelesai);
    const endTime = exam.jamSelesai || '00:00';

    let buttonHtml;
    if (exam.status === 'AKTIF') {
        buttonHtml = `<a href="${exam.link}" class="exam-button">🚀 Buka Ujian</a>`;
    } else { 
        const fullStartTime = `${exam.tanggalMulai} ${startTime}`;
        buttonHtml = `
            <div class="disabled-button-container">
                <a href="#" class="exam-button disabled">🔒 Segera Hadir</a>
                <p class="countdown-text" data-start-time="${fullStartTime}"></p>
            </div>`;
    }

    card.innerHTML = `
        <div class="exam-header">
            <h3 class="subject-name">${number}. ${exam.mapel}</h3>
            <span class="class-badge">${displayClass}</span>
        </div>
        <div class="schedule-info">
            <div class="schedule-title">📅 Jadwal Ujian</div>
            <div class="date-range">
                <span class="date">${startDate} ${startTime}</span>
                <span class="arrow">→</span>
                <span class="date">${endDate} ${endTime}</span>
            </div>
        </div>
        ${buttonHtml}
    `;
    return card;
}

function updateCountdowns() {
    const countdownElements = document.querySelectorAll('.countdown-text');
    countdownElements.forEach(element => {
        const startTimeStr = element.getAttribute('data-start-time');
        const startTime = parseDateManual(startTimeStr);
        const now = new Date();

        if (!startTime) return;

        const diff = startTime - now;

        if (diff <= 0) {
            element.textContent = 'Memuat ulang...';
            if(window.Android) {
                Android.reloadPage();
            }
        } else {
            const hours = Math.floor(diff / (1000 * 60 * 60));
            const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));
            const seconds = Math.floor((diff % (1000 * 60)) / 1000);

            let countdownString = 'dimulai dalam ';
            if (hours > 0) countdownString += `${hours}j `;
            if (minutes > 0) countdownString += `${minutes}m `;
            countdownString += `${seconds}d`;

            element.textContent = countdownString;
        }
    });
}

function parseDateManual(dateStr) {
    try {
        const parts = dateStr.split(' ');
        const dateParts = parts[0].split('-');
        const timeParts = parts[1].split(':');
        return new Date(dateParts[0], dateParts[1] - 1, dateParts[2], timeParts[0], timeParts[1]);
    } catch (e) {
        return null;
    }
}

function safeFormatDate(dateString) {
    if (!dateString) return "–";
    const date = parseDateManual(dateString + " 00:00");
    if (!date || isNaN(date)) return dateString;
    return date.toLocaleDateString('id-ID', { day:'numeric', month:'short', year:'numeric' });
}

function updateExamList(userInfo, examList) {
    cancelWatchdog();
    document.getElementById('infoBox').style.display = 'none';
    initializeUI(userInfo, examList);
}

function showInfoMessage(title, message) {
    // Matikan watchdog segera saat menampilkan pesan manual
    cancelWatchdog();
    
    const examGrid = document.getElementById('examGrid');
    const infoBox = document.getElementById('infoBox');
    const noExams = document.getElementById('noExams');
    
    examGrid.style.display = 'none';
    noExams.style.display = 'none';
    infoBox.style.display = 'flex';
    
    document.getElementById('infoTitle').textContent = title;
    document.getElementById('infoMessage').innerHTML = message;
}
